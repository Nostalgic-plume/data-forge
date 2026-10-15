package com.dataforge.pipeline;

import com.dataforge.cache.GenerationCache;
import com.dataforge.classifier.ColumnClassifier;
import com.dataforge.config.GeneratorProperties;
import com.dataforge.expansion.ExpansionEngine;
import com.dataforge.expansion.FixedSamples;
import com.dataforge.generator.RuleDataGenerator;
import com.dataforge.llm.DataLlm;
import com.dataforge.llm.PromptBuilder;
import com.dataforge.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Phase C：DAG 数据流内容生成（移植 real-world-data ContentGenPhase，瘦身 + 5 优化）。
 *
 * <p><b>表间调度</b>：入度递减 + 虚拟线程，父表完成后立即启动子表（同层并行）。</p>
 * <p><b>表内批次</b>：预切批（batchSize），Semaphore(maxConcurrency) 限流并行，按序合并。</p>
 *
 * <p><b>5 优化落点：</b>
 * <ol>
 *   <li>优化①补差不重试：批内 LLM 行数缺口 ≤2% 接受；&gt;2% 只补缺失段，不整批重试</li>
 *   <li>优化②batch 自适应：批内失败 → count/2 降批递归（下限 MIN_BATCH），记录降批次数</li>
 *   <li>优化③④生成缓存/断点续跑：runFingerprint 表产物落盘，命中直接复用</li>
 *   <li>优化⑤失败表隔离：单表失败 → failedTables，其余继续；失败父表的 FK 不注入子表</li>
 * </ol></p>
 *
 * <p>信任模型红线：LLM 只产语义列值；PK/FK 注入、行内后处理、对账全是确定性代码。</p>
 */
public final class ContentGen {

    private static final Logger log = LoggerFactory.getLogger(ContentGen.class);

    /** batch 自适应降批下限（再小就不值得降，直接报失败） */
    static final int MIN_BATCH = 5;
    /** 补差不重试阈值：缺口占比 ≤2% 直接接受 */
    static final double GAP_ACCEPT_RATIO = 0.02;

    /** 单表生成结果（CLI 汇总输出用） */
    public record TableResult(
            GeneratedData data,
            long elapsedMs,
            int llmBatches,     // LLM 调用批次数（主 + 补差）
            int downshifts,     // batch 自适应降批次数
            int gapFills,       // 补差次数
            boolean cacheHit    // 是否缓存命中（断点续跑/缓存复用）
    ) {
    }

    /** 整次生成结果：各表产物 + 失败表清单（失败隔离不 abort 全局） */
    public record GenOutcome(Map<String, TableResult> results, List<String> failedTables) {
        public int totalRows() {
            return results.values().stream().mapToInt(r -> r.data().rowCount()).sum();
        }
    }

    private final PipelineContext context;
    private final DataLlm llm;
    private final GeneratorProperties props;
    private final GenerationCache cache;        // null = --no-cache
    private final ExpansionEngine expansion;    // null = 纯 LLM 模式（P0 兼容）
    private final TableCompletedListener listener;  // null = 非流水线（P0-P3 兼容）
    private final ProjectRules rules;           // 分析产物：generationRules 注入生成 prompt（决策 #21）
    private Map<String, Map<String, List<Object>>> initialCompleted;  // P5 增量现有池（缓存 key 用）
    private String runFingerprint;              // null = --no-cache

    /** 已完成表的列值索引（子表 FK 上下文 / 展开器样本用，并发安全） */
    private final Map<String, Map<String, List<Object>>> completedData = new ConcurrentHashMap<>();
    /** 失败表（并发安全；失败表隔离：单表失败不 abort 全局） */
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    /** 生成结果 */
    private final Map<String, TableResult> results = new ConcurrentHashMap<>();

    private ContentGen(PipelineContext context, DataLlm llm, GeneratorProperties props,
                       GenerationCache cache, ExpansionEngine expansion,
                       TableCompletedListener listener, ProjectRules rules) {
        this.context = context;
        this.llm = llm;
        this.props = props;
        this.cache = cache;
        this.expansion = expansion;
        this.listener = listener;
        this.rules = rules;
    }

    public static GenOutcome execute(PipelineContext context, DataLlm llm,
                                     GeneratorProperties props, GenerationCache cache,
                                     ExpansionEngine expansion) {
        return execute(context, llm, props, cache, expansion, null, null);
    }

    /** P4 流水线版：传入表完成事件监听器（边生成边落库边修正） */
    public static GenOutcome execute(PipelineContext context, DataLlm llm,
                                     GeneratorProperties props, GenerationCache cache,
                                     ExpansionEngine expansion, TableCompletedListener listener) {
        return execute(context, llm, props, cache, expansion, listener, null);
    }

    /**
     * P5 增量版：initialCompleted 为现有父表数据的列值索引（目标库/staging 现有行），
     * 预填 completedData——展开器/LLM prompt 可跨现有引用（现有池引用）。
     */
    public static GenOutcome execute(PipelineContext context, DataLlm llm,
                                     GeneratorProperties props, GenerationCache cache,
                                     ExpansionEngine expansion, TableCompletedListener listener,
                                     Map<String, Map<String, List<Object>>> initialCompleted) {
        return execute(context, llm, props, cache, expansion, listener, initialCompleted,
                ProjectRules.empty());
    }

    /**
     * 规则注入版（决策 #21）：rules 的 generationRules 注入生成 prompt（前置约束节）。
     * DefaultGenerationAgent 全量/增量生成走此签名；无规则时等价旧签名。
     */
    public static GenOutcome execute(PipelineContext context, DataLlm llm,
                                     GeneratorProperties props, GenerationCache cache,
                                     ExpansionEngine expansion, TableCompletedListener listener,
                                     Map<String, Map<String, List<Object>>> initialCompleted,
                                     ProjectRules rules) {
        ContentGen gen = new ContentGen(context, llm, props, cache, expansion, listener, rules);
        if (initialCompleted != null && !initialCompleted.isEmpty()) {
            gen.completedData.putAll(initialCompleted);
            gen.initialCompleted = initialCompleted;
            log.info("增量生成：预填现有父表池 {} 张表（跨现有引用）", initialCompleted.size());
        }
        return gen.run();
    }

    /** 从现有数据构造列值索引（增量现有池 / 跨现有引用） */
    public static Map<String, Map<String, List<Object>>> buildCompletedIndex(
            Map<String, com.dataforge.model.GeneratedData> baseline) {
        Map<String, Map<String, List<Object>>> index = new LinkedHashMap<>();
        for (com.dataforge.model.GeneratedData data : baseline.values()) {
            Map<String, List<Object>> record = new LinkedHashMap<>();
            for (ColumnDef col : data.columns()) {
                List<Object> values = new ArrayList<>(data.rowCount());
                for (DataRow row : data.rows()) {
                    values.add(row.values().get(col.name()));
                }
                record.put(col.name(), values);
            }
            index.put(data.tableName(), record);
        }
        return index;
    }

    // =============================================================
    //  DAG 调度
    // =============================================================

    /** 增量现有数据特征（每表行数，供缓存 key），目标库变化 → 缓存失效 */
    private String baselineSuffix() {
        if (initialCompleted == null || initialCompleted.isEmpty()) return null;
        return initialCompleted.keySet().stream().sorted()
                .map(k -> k + "=" + (initialCompleted.get(k).get("id") == null
                        ? 0 : initialCompleted.get(k).get("id").size()))
                .collect(Collectors.joining(","));
    }

    private GenOutcome run() {
        if (cache != null) {
            this.runFingerprint = GenerationCache.computeRunFingerprint(
                    context.tables(), context.rowCounts(), props, llm, expansion != null,
                    baselineSuffix());
            log.info("runFingerprint = {}（同参数重跑即缓存/断点续跑；增量含 baseline 特征）", runFingerprint);
        }

        Map<String, TableMeta> tableMap = context.tables().stream()
                .collect(Collectors.toMap(TableMeta::name, t -> t));
        Map<String, Set<String>> depGraph = TopologicalSorter.dependencyGraph(context.tables());

        // 环检测：未被拓扑排序覆盖的表 = FK 环（shop 无环，兜底逻辑）
        Set<String> ordered = TopologicalSorter.sort(context.tables()).stream()
                .flatMap(List::stream).map(TableMeta::name).collect(Collectors.toSet());
        Set<String> cyclic = context.tables().stream()
                .map(TableMeta::name).filter(n -> !ordered.contains(n)).collect(Collectors.toSet());
        for (String c : cyclic) {
            log.error("检测到 FK 环 `{}`（P0 不支持环内联合生成，标记失败）", c);
        }

        // 入度 = 生成集内的直接父表数
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (TableMeta t : context.tables()) {
            inDegree.put(t.name(), (int) depGraph.get(t.name()).stream().filter(tableMap::containsKey).count());
        }

        Set<String> started = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(context.tables().size());
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
                if (entry.getValue() == 0) {
                    tryStartTable(entry.getKey(), tableMap, inDegree, depGraph, latch, started, pool, cyclic);
                }
            }
            if (started.isEmpty()) {
                failed.addAll(inDegree.keySet());
                log.error("无表可启动（全部构成 FK 环），本次生成全部标记失败");
            }
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("ContentGen 被中断", e);
            }
        } finally {
            pool.shutdown();
        }

        List<String> failedTables = failed.stream().sorted().toList();
        if (!failedTables.isEmpty()) {
            log.warn("失败表隔离汇总：{} 张表失败 = {}", failedTables.size(), failedTables);
        }
        return new GenOutcome(results, failedTables);
    }

    private void tryStartTable(String tableName, Map<String, TableMeta> tableMap,
                               Map<String, Integer> inDegree, Map<String, Set<String>> depGraph,
                               CountDownLatch latch, Set<String> started,
                               ExecutorService pool, Set<String> cyclic) {
        if (!started.add(tableName)) return;
        CompletableFuture.runAsync(() -> {
            try {
                if (cyclic.contains(tableName)) {
                    failed.add(tableName);
                    log.warn("环内表 `{}` 标记失败（P0 不做环内联合生成）", tableName);
                    return;
                }
                TableResult result = generateTable(tableMap.get(tableName));
                results.put(tableName, result);
                // P4 流水线：表完成事件（小表修正不阻塞大表生成）
                if (listener != null) {
                    listener.onTableCompleted(tableName, result.data());
                }
            } catch (Exception e) {
                // 优化⑤失败表隔离：单表失败只标记，不 cancel 全局
                failed.add(tableName);
                log.error("表 `{}` 生成失败（失败隔离，继续其他表）: {}", tableName, e.getMessage());
            } finally {
                synchronized (inDegree) {
                    for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
                        String child = entry.getKey();
                        if (depGraph.get(child).contains(tableName)) {
                            int newDegree = entry.getValue() - 1;
                            entry.setValue(newDegree);
                            if (newDegree == 0 && !child.equals(tableName)) {
                                tryStartTable(child, tableMap, inDegree, depGraph, latch, started, pool, cyclic);
                            }
                        }
                    }
                }
                latch.countDown();
            }
        }, pool);
    }

    // =============================================================
    //  单表生成
    // =============================================================

    private TableResult generateTable(TableMeta table) {
        long start = System.currentTimeMillis();
        String tableName = table.name();
        int total = context.rowCount(tableName);
        if (total <= 0) {
            return new TableResult(new GeneratedData(tableName, table.columns(), List.of()),
                    System.currentTimeMillis() - start, 0, 0, 0, false);
        }

        // 优化③④：缓存命中 → 直接复用（断点续跑 = 同 fingerprint 下已落盘表跳过）
        if (cache != null) {
            Optional<GeneratedData> cached = cache.load(runFingerprint, table);
            if (cached.isPresent()) {
                recordCompletedData(table, cached.get());
                return new TableResult(cached.get(), System.currentTimeMillis() - start, 0, 0, 0, true);
            }
        }

        // P1 分支①：固定样本表（role/admin，语义硬编码，零 LLM）
        if (expansion != null && expansion.isFixed(context.plan(), tableName)) {
            List<Map<String, Object>> fixedRows = FixedSamples.rows(
                    tableName, table.columns(), context.newRandom());
            List<DataRow> rows = fixedRows.stream()
                    .map(m -> new DataRow(m, lineageAll(table, SourceTag.EXPAND)))
                    .toList();
            PostProcessor.process(rows.stream().map(DataRow::values).toList(), table.columns());
            GeneratedData data = new GeneratedData(tableName, table.columns(), rows);
            recordCompletedData(table, data);
            if (cache != null) cache.save(runFingerprint, data);
            log.info("表 `{}` 固定样本生成 {} 行（零 LLM）", tableName, rows.size());
            return new TableResult(data, System.currentTimeMillis() - start, 0, 0, 0, false);
        }

        // P1 分支②：展开表（派生表，锚点 + 蓝图规则实例化，零 LLM）
        if (expansion != null && expansion.isExpanded(context.plan(), tableName)) {
            List<DataRow> expanded = expansion.expand(table, context, completedData);
            injectPkValues(expanded, table); // 展开器行 id=null，注入骨架 PK
            // 通用化修复：语义列（商品名/规格等）由 LLM 批量回填（配置驱动通用，避免人名/占位）
            fillLlmColumns(table, expanded);
            PostProcessor.process(expanded.stream().map(DataRow::values).toList(), table.columns());
            GeneratedData data = new GeneratedData(tableName, table.columns(), expanded);
            recordCompletedData(table, data);
            if (cache != null) cache.save(runFingerprint, data);
            log.info("表 `{}` 展开生成 {} 行（零 LLM，锚点派生）", tableName, expanded.size());
            return new TableResult(data, System.currentTimeMillis() - start, 0, 0, 0, false);
        }

        // P1 分支③：LLM 语义源表（样本）——生成 + 样本预检（早期止损，失败重生成 ≤2 次）
        Map<String, List<Object>> usableFkAlloc = buildUsableFkAlloc(table, tableName,
                context.fkAllocations().get(tableName));
        String parentContext = PromptBuilder.buildParentContext(
                table.name(), table.columns(), table.foreignKeys(), completedData);

        List<String> preflightErrors = List.of();
        LlmResult llmResult = null;
        for (int attempt = 1; attempt <= MAX_SAMPLE_ATTEMPTS; attempt++) {
            llmResult = generateLlmTable(table, usableFkAlloc, parentContext, total);
            if (expansion == null) break; // 纯 LLM 模式（P0 兼容）不预检
            // 唯一键确定性去重（最小干预修正，避免整表重生成的重试放大）
            UniqueDeduper.dedupe(llmResult.data().rows(), table);
            preflightErrors = SamplePreflight.preflight(table, llmResult.data(), total, completedData);
            if (preflightErrors.isEmpty()) break;
            log.warn("表 `{}` 样本预检失败（第 {} 次，早期止损）: {}", tableName, attempt, preflightErrors);
        }
        if (!preflightErrors.isEmpty()) {
            throw new IllegalStateException("表 `" + tableName + "` 样本预检 "
                    + MAX_SAMPLE_ATTEMPTS + " 次失败: " + preflightErrors);
        }

        recordCompletedData(table, llmResult.data());
        if (cache != null) cache.save(runFingerprint, llmResult.data());
        return new TableResult(llmResult.data(), System.currentTimeMillis() - start,
                llmResult.llmBatches(), llmResult.downshifts(), llmResult.gapFills(), false);
    }

    // =============================================================
    //  LLM 语义源表生成（样本）
    // =============================================================

    /** 样本预检最大尝试次数 */
    private static final int MAX_SAMPLE_ATTEMPTS = 3;

    /** LLM 生成结果（数据 + 批次统计） */
    private record LlmResult(GeneratedData data, int llmBatches, int downshifts, int gapFills) {
    }

    /** 生成一张 LLM 语义源表的完整样本（列分类 + 批次并行 + 补差/降批 + 行内后处理） */
    private LlmResult generateLlmTable(TableMeta table, Map<String, List<Object>> usableFkAlloc,
                                       String parentContext, int total) {
        String tableName = table.name();
        // 列分类（rule 优先：能确定性生成的列先排除，其余才是 LLM；语义字符串防线内置在 classifyLlmColumns）
        List<String> fkCols = table.fkColumnNames();
        List<ColumnDef> llmCols = new ArrayList<>(classifyLlmColumns(table, fkCols));
        Set<String> llmNames = llmCols.stream().map(ColumnDef::name).collect(Collectors.toSet());
        List<ColumnDef> localRuleCols = table.columns().stream()
                .filter(c -> !c.primaryKey() && !fkCols.contains(c.name()) && !llmNames.contains(c.name()))
                .collect(Collectors.toList());

        List<DataRow> allRows;
        int llmBatches = 0, downshifts = 0, gapFills = 0;

        if (llmCols.isEmpty()) {
            // 纯规则表：零 LLM 直出（确定性）
            List<Map<String, Object>> ruleRows = RuleDataGenerator.generateRuleValues(
                    localRuleCols, total, context.newRandom());
            injectPkAndFk(ruleRows, table, usableFkAlloc, 0);
            allRows = mergeAndTag(table, List.of(), ruleRows, List.of());
            log.info("表 `{}` 纯规则列生成 {} 行（零 LLM）", tableName, allRows.size());
        } else {
            // 批次规划
            int batchSize = Math.max(1, Math.min(props.batchSize(), total));
            List<int[]> batches = new ArrayList<>();
            for (int batchStart = 0; batchStart < total; batchStart += batchSize) {
                batches.add(new int[]{batchStart, Math.min(batchSize, total - batchStart)});
            }

            // 表内批次并行（Semaphore 限流 maxConcurrency 防限流），按批序号排序合并
            Semaphore semaphore = new Semaphore(Math.max(1, props.maxConcurrency()));
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            try {
                List<CompletableFuture<BatchResult>> futures = new ArrayList<>();
                for (int[] batch : batches) {
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            semaphore.acquire();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        try {
                            return runBatch(table, localRuleCols, llmCols, usableFkAlloc,
                                    parentContext, batch[0], batch[1]);
                        } finally {
                            semaphore.release();
                        }
                    }, pool));
                }
                List<BatchResult> batchResults = futures.stream().map(CompletableFuture::join).toList();
                allRows = new ArrayList<>(total);
                for (BatchResult br : batchResults) {
                    allRows.addAll(br.rows());
                    llmBatches += br.llmCalls();
                    downshifts += br.downshifts();
                    gapFills += br.gapFills();
                }
            } finally {
                pool.shutdown();
            }
        }

        // 行内后处理（时间顺序/价格顺序/状态-时间联动，确定性）
        PostProcessor.process(allRows.stream().map(DataRow::values).toList(), table.columns());
        return new LlmResult(new GeneratedData(tableName, table.columns(), allRows),
                llmBatches, downshifts, gapFills);
    }

    /** 全列血缘标注（固定/展开行用） */
    private static Map<String, SourceTag> lineageAll(TableMeta table, SourceTag tag) {
        Map<String, SourceTag> lineage = new LinkedHashMap<>();
        for (ColumnDef col : table.columns()) {
            lineage.put(col.name(), tag);
        }
        return lineage;
    }

    /** 注入 PK 骨架值（展开/固定行的 id=null → 骨架按行序填充） */
    private void injectPkValues(List<DataRow> rows, TableMeta table) {
        Map<String, List<Object>> pkSkeleton = context.pkSkeletons().get(table.name());
        if (pkSkeleton == null) return;
        for (ColumnDef pkCol : table.columns().stream().filter(ColumnDef::primaryKey).toList()) {
            List<Object> pkValues = pkSkeleton.get(pkCol.name());
            if (pkValues == null) continue;
            for (int i = 0; i < rows.size() && i < pkValues.size(); i++) {
                rows.get(i).values().put(pkCol.name(), pkValues.get(i));
            }
        }
    }

    // =============================================================
    //  批次执行（补差 + batch 自适应落点）
    // =============================================================

    /** 过滤失败父表的 FK 分配（父表失败 → 子表该 FK 列不注入，悬挂由校验报告） */
    private Map<String, List<Object>> buildUsableFkAlloc(TableMeta table, String tableName,
                                                         Map<String, List<Object>> fkAlloc) {
        if (fkAlloc == null) return null;
        Map<String, List<Object>> usable = new HashMap<>();
        for (Map.Entry<String, List<Object>> e : fkAlloc.entrySet()) {
            String fkCol = e.getKey();
            String refTable = table.foreignKeys().stream()
                    .filter(fk -> fk.column().equals(fkCol)).map(ForeignKeyDef::refTable)
                    .findFirst().orElse(null);
            if (refTable != null && failed.contains(refTable)) {
                log.warn("父表 `{}` 失败 → 子表 `{}`.`{}` FK 不注入（校验将报告悬挂）",
                        refTable, tableName, fkCol);
                continue;
            }
            usable.put(fkCol, e.getValue());
        }
        return usable;
    }

    /** 批次结果：行 + 统计（LLM 调用次数 / 降批次数 / 补差次数） */
    private record BatchResult(List<DataRow> rows, int llmCalls, int downshifts, int gapFills) {
    }

    private BatchResult runBatch(TableMeta table, List<ColumnDef> localRuleCols, List<ColumnDef> llmCols,
                                 Map<String, List<Object>> usableFkAlloc, String parentContext,
                                 int startRow, int count) {
        try {
            return generateBatchOnce(table, localRuleCols, llmCols, usableFkAlloc,
                    parentContext, startRow, count);
        } catch (Exception e) {
            // 优化②batch 自适应：失败 → 降批递归（拆两半），下限 MIN_BATCH
            if (count <= MIN_BATCH) {
                throw new IllegalStateException("表 `" + table.name() + "` 批次[" + startRow + ",+" + count
                        + "] 降批至下限仍失败: " + e.getMessage(), e);
            }
            int half = Math.max(MIN_BATCH, count / 2);
            log.warn("表 `{}` 批次[{}..{}) 失败（{}），batch 自适应降批 → [{}, {}) + [{}, {})",
                    table.name(), startRow, startRow + count, e.getMessage(),
                    startRow, startRow + half, startRow + half, startRow + count);
            BatchResult first = runBatch(table, localRuleCols, llmCols, usableFkAlloc,
                    parentContext, startRow, half);
            BatchResult second = runBatch(table, localRuleCols, llmCols, usableFkAlloc,
                    parentContext, startRow + half, count - half);
            List<DataRow> rows = new ArrayList<>(first.rows().size() + second.rows().size());
            rows.addAll(first.rows());
            rows.addAll(second.rows());
            return new BatchResult(rows, first.llmCalls() + second.llmCalls(),
                    first.downshifts() + second.downshifts() + 1,
                    first.gapFills() + second.gapFills());
        }
    }

    /** 批次随机源盐值：表名 + 起始行号（表间、批次间均不重复，且与执行顺序无关 → 结果可复现） */
    private static long batchSalt(String tableName, int startRow) {
        return (long) tableName.hashCode() * 1_000_003L + startRow;
    }

    private BatchResult generateBatchOnce(TableMeta table, List<ColumnDef> localRuleCols,
                                          List<ColumnDef> llmCols, Map<String, List<Object>> usableFkAlloc,
                                          String parentContext, int startRow, int count) {
        // 1. 规则列（确定性）→ 2. PK 骨架 + FK 预分配注入（定位 startRow）
        //    随机源按"表 + 批起点"派生：各批必须拿到不同序列，否则每批本地列整批重复
        //    （见 PipelineContext#newRandom(long)）
        List<Map<String, Object>> ruleRows = RuleDataGenerator.generateRuleValues(
                localRuleCols, count, context.newRandom(batchSalt(table.name(), startRow)));
        injectPkAndFk(ruleRows, table, usableFkAlloc, startRow);

        // 3. LLM 语义列（父表上下文 + 前置约束注入，决策 #21）
        String promptContext = mergePromptContext(parentContext, tableConstraints(table.name()));
        List<Map<String, Object>> llmRows = llm.generateRows(table.name(), llmCols, count, promptContext);
        int llmCalls = 1;

        // 4. 合并（规则列与 LLM 列按下标对齐，以短者为准）
        List<DataRow> rows = mergeAndTag(table, llmCols, ruleRows, llmRows);
        int got = rows.size();

        // 优化①补差不重试：缺口 >2% 只补缺失段（batch=缺口），不整批重试；≤2% 直接接受
        if (got < count) {
            int gap = count - got;
            if (gap * 100 > count * GAP_ACCEPT_RATIO * 100) {
                List<Map<String, Object>> gapRuleRows = RuleDataGenerator.generateRuleValues(
                        localRuleCols, gap, context.newRandom(batchSalt(table.name(), startRow + got)));
                injectPkAndFk(gapRuleRows, table, usableFkAlloc, startRow + got);
                List<Map<String, Object>> gapLlmRows = llm.generateRows(table.name(), llmCols, gap, promptContext);
                llmCalls++;
                List<DataRow> gapRows = mergeAndTag(table, llmCols, gapRuleRows, gapLlmRows);
                rows.addAll(gapRows);
                log.info("表 `{}` 批次[{}..{}) 补差: {} → 补 {} 行（现 {} 行，目标 {}）",
                        table.name(), startRow, startRow + count, got, gap, rows.size(), count);
                return new BatchResult(rows, llmCalls, 0, 1);
            }
            log.info("表 `{}` 批次缺 {} 行（≤2%，补差不重试，接受）", table.name(), gap);
        }
        return new BatchResult(rows, llmCalls, 0, 0);
    }

    // =============================================================
    //  行组装 / 注入 / 血缘
    // =============================================================

    /**
     * 合并规则列 + LLM 列并标注血缘（规则列与 LLM 列按下标对齐，以短者为准）。
     *
     * @param llmCols   LLM 语义列（血缘标注依据；空 = 纯规则表）
     * @param ruleRows  规则列行（含已注入的 PK/FK）
     * @param llmRows   LLM 列行（可能缺行/多行）
     */
    private List<DataRow> mergeAndTag(TableMeta table, List<ColumnDef> llmCols,
                                      List<Map<String, Object>> ruleRows,
                                      List<Map<String, Object>> llmRows) {
        Set<String> llmColNames = llmCols.stream().map(ColumnDef::name).collect(Collectors.toSet());
        Map<String, SourceTag> tagByColumn = new LinkedHashMap<>();
        for (ColumnDef col : table.columns()) {
            SourceTag tag;
            if (col.primaryKey()) tag = SourceTag.PK;
            else if (table.fkColumnNames().contains(col.name())) tag = SourceTag.FK;
            else if (llmColNames.contains(col.name())) tag = SourceTag.LLM;
            else tag = SourceTag.RULE;
            tagByColumn.put(col.name(), tag);
        }

        int count = Math.min(ruleRows.size(), llmRows.isEmpty() ? ruleRows.size() : llmRows.size());
        List<DataRow> rows = new ArrayList<>(count);
        for (int j = 0; j < count; j++) {
            Map<String, Object> values = new LinkedHashMap<>();
            Map<String, SourceTag> lineage = new LinkedHashMap<>();
            for (ColumnDef col : table.columns()) {
                Object v = ruleRows.get(j).get(col.name());
                if (v == null && j < llmRows.size()) {
                    v = llmRows.get(j).get(col.name());
                }
                if (v != null) {
                    values.put(col.name(), v);
                    lineage.put(col.name(), tagByColumn.get(col.name()));
                }
            }
            rows.add(new DataRow(values, lineage));
        }
        return rows;
    }

    /** 注入 PK 骨架值（跨批次连续）+ FK 预分配值（定位 startRow 偏移） */
    private void injectPkAndFk(List<Map<String, Object>> ruleRows, TableMeta table,
                               Map<String, List<Object>> usableFkAlloc, int startRow) {
        Map<String, List<Object>> pkSkeleton = context.pkSkeletons().get(table.name());
        if (pkSkeleton != null) {
            for (ColumnDef pkCol : table.columns().stream().filter(ColumnDef::primaryKey).toList()) {
                List<Object> pkValues = pkSkeleton.get(pkCol.name());
                if (pkValues == null) continue;
                for (int i = 0; i < ruleRows.size() && (startRow + i) < pkValues.size(); i++) {
                    ruleRows.get(i).put(pkCol.name(), pkValues.get(startRow + i));
                }
            }
        }
        if (usableFkAlloc != null) {
            for (Map.Entry<String, List<Object>> e : usableFkAlloc.entrySet()) {
                List<Object> fkValues = e.getValue();
                for (int i = 0; i < ruleRows.size() && (startRow + i) < fkValues.size(); i++) {
                    ruleRows.get(i).put(e.getKey(), fkValues.get(startRow + i));
                }
            }
        }
    }

    /**
     * 派生表语义列 LLM 回填（质量闭环，杜绝静默占位）：完整回填 + 补差 + 缺失抛异常。
     *
     * <p>"宁可失败，不可静默"：LLM 回填行数不足 → 补差一次仍不足 → 抛异常（失败表隔离）；
     * 任一 llm 列值为 null → 抛异常。占位比失败危险——失败可见、可重生成，占位会静默污染数据。</p>
     */
    private void fillLlmColumns(TableMeta table, List<DataRow> rows) {
        List<ColumnDef> llmCols = expansion.llmColumns(context.plan(), table);
        if (llmCols.isEmpty() || rows.isEmpty()) return;
        List<Map<String, Object>> llmRows = fillByParentGroup(table, llmCols, rows);
        for (int i = 0; i < rows.size(); i++) {
            for (ColumnDef c : llmCols) {
                Object v = llmRows.get(i).get(c.name());
                if (v == null) {
                    throw new IllegalStateException("表 `" + table.name() + "` 语义列 `" + c.name()
                            + "` 回填缺失，标记失败（绝不静默占位）");
                }
                rows.get(i).values().put(c.name(), v);
                rows.get(i).lineage().put(c.name(), SourceTag.LLM);
            }
        }
    }

    /**
     * 按父表分组回填语义列——<b>父驱动的生成</b>：分类是 product 的父表，商品就该在分类之下
     * 生成。像 sku 这种"隔代"表（直接父是 product、祖父才是 category），沿父链上溯找到
     * "足够小的祖先"作分组依据。
     *
     * <p>不分组的话，整表共用一段 prompt、而外键是随机的，两者永不相干：商品名千篇一律，
     * 价格与规格也不随品类变化。</p>
     *
     * <p><b>判据保守</b>：只有祖先表行数不足本表 1/10 才用它分组；最多上溯两层，找不到就
     * 退回整表回填。否则像 orders(3000) 按 user(15000) 分会被切成大量碎片，每批过小、
     * 调用数暴涨，得不偿失。</p>
     *
     * <p><b>行序必须严格对齐</b>：按原行下标收集分组结果再还原——{@code fillBatched} 的注释
     * 强调过，顺序错乱会把商品名配到别的商品上，比缺值更危险。</p>
     */
    private List<Map<String, Object>> fillByParentGroup(TableMeta table, List<ColumnDef> llmCols,
                                                        List<DataRow> rows) {
        String parent = parentOf(table.name());
        String fkCol = parent == null ? null : fkTo(table.name(), parent);
        if (parent == null || fkCol == null) {
            return fillRowsWithGapFill(table, llmCols, rows.size(), null);
        }
        // ① 直接父表够小 → 按它分组（如 product 按 category）
        if (smallEnough(parent, rows.size())) {
            return bucketedFill(table, llmCols, rows, fkCol, idToName(parent), parent, null);
        }
        // ② 父表太大、祖父够小 → 按祖父分组（如 sku 按 category；经父表上溯一级）
        String grand = parentOf(parent);
        String upFk = grand == null ? null : fkTo(parent, grand);
        if (grand != null && upFk != null && smallEnough(grand, rows.size())) {
            Map<Object, Object> toGrand = idMap(parent, upFk);   // 父行 id → 祖先行 id
            if (!toGrand.isEmpty()) {
                return bucketedFill(table, llmCols, rows, fkCol, idToName(grand), grand, toGrand);
            }
        }
        // 有父表却没分组：语义列会退化成"整表一段 prompt"，内容与父类的对应关系随之变弱。
        // 这条静默降级最坑——数据看着正常，只是商品和分类对不上，事后无从察觉，故必须留痕。
        log.warn("表 `{}` 的语义列未按父表 `{}` 分组：父表行数×10 > 本表 {} 行。"
                        + "本次将整表一次性生成，不携带父类上下文；如需按父类生成请调大本表行数",
                table.name(), parent, rows.size());
        return fillRowsWithGapFill(table, llmCols, rows.size(), null);
    }

    /**
     * 按分组键分桶回填。{@code upMap} 非空时，先经它把本表外键值上溯到祖先行 id 再取名称；
     * 结果按原行下标还原（不留 null）。
     */
    private List<Map<String, Object>> bucketedFill(TableMeta table, List<ColumnDef> llmCols,
                                                   List<DataRow> rows, String fkCol,
                                                   Map<Object, String> nameOf, String groupTable,
                                                   Map<Object, Object> upMap) {
        Map<String, List<Integer>> buckets = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Object key = rows.get(i).values().get(fkCol);
            if (upMap != null && key != null) key = upMap.get(key);
            buckets.computeIfAbsent(key == null ? "（未分类）" : nameOf.getOrDefault(key, String.valueOf(key)),
                    k -> new ArrayList<>()).add(i);
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) out.add(null);
        for (Map.Entry<String, List<Integer>> e : buckets.entrySet()) {
            List<Integer> idx = e.getValue();
            String hint = "本批 " + table.name() + " 记录全部属于分类「" + e.getKey()
                    + "」，生成内容必须与该分类一致（名称、规格、价格都要像该分类下真实存在的"
                    + "商品，不得跨品类）。";
            List<Map<String, Object>> part = fillRowsWithGapFill(table, llmCols, idx.size(), hint);
            for (int k = 0; k < idx.size() && k < part.size(); k++) {
                out.set(idx.get(k), part.get(k));   // 按下标还原，行序不错位
            }
        }
        log.info("表 `{}` 语义列按 `{}` 分 {} 组回填（父驱动生成）", table.name(), groupTable, buckets.size());
        return out;
    }

    /** plan 里该表的父表名（无 plan 或无父表返回 null） */
    private String parentOf(String tableName) {
        GenerationPlan.TablePlan tp = context.plan() == null ? null : context.plan().tablePlan(tableName);
        return tp == null ? null : tp.parent();
    }

    /** fromTable 指向 toTable 的外键列名（无则 null）——中间表也要能查，故走 context 的元数据 */
    private String fkTo(String fromTable, String toTable) {
        TableMeta meta = context.table(fromTable);
        if (meta == null) return null;
        return meta.foreignKeys().stream()
                .filter(fk -> toTable.equals(fk.refTable()))
                .map(com.dataforge.model.ForeignKeyDef::column).findFirst().orElse(null);
    }

    /** 该表是否"足够小"到可作分组依据：行数不足当前表 1/10 */
    private boolean smallEnough(String tableName, int currentRows) {
        Map<String, List<Object>> d = completedData.get(tableName);
        List<Object> ids = d == null ? null : d.get("id");
        return ids != null && !ids.isEmpty() && ids.size() * 10 <= currentRows;
    }

    /** 该表的 行id → name 映射（无 name 列则空） */
    private Map<Object, String> idToName(String tableName) {
        Map<String, List<Object>> d = completedData.get(tableName);
        if (d == null || d.get("id") == null || d.get("name") == null) return Map.of();
        Map<Object, String> m = new HashMap<>();
        List<Object> ids = d.get("id");
        List<Object> names = d.get("name");
        for (int i = 0; i < ids.size() && i < names.size(); i++) {
            m.put(ids.get(i), String.valueOf(names.get(i)));
        }
        return m;
    }

    /** 该表 行id → 指定列值 的映射（沿父链上溯用） */
    private Map<Object, Object> idMap(String tableName, String column) {
        Map<String, List<Object>> d = completedData.get(tableName);
        if (d == null || d.get("id") == null || d.get(column) == null) return Map.of();
        Map<Object, Object> m = new HashMap<>();
        List<Object> ids = d.get("id");
        List<Object> vals = d.get(column);
        for (int i = 0; i < ids.size() && i < vals.size(); i++) {
            if (vals.get(i) != null) m.put(ids.get(i), vals.get(i));
        }
        return m;
    }

    /**
     * 完整回填 + 补差（LLM 返回不足 → 补缺段；仍不足 → 抛异常，绝不静默接受缺口）。
     *
     * <p><b>必须分批</b>：本方法此前把整表行数一次性交给 LLM，与 source 表
     * （generateLlmTable 按 batchSize 切批）实现不一致。大表因此必然触发 180s 超时装饰器
     * ——实测 product 300 行 × 4 列超时，随后 failover 到未配置密钥的备模型，
     * 以 404 收场、整表失败并连累依赖它的表。小规模（60 行）恰好卡在超时线内，故长期未暴露。</p>
     *
     * <p>串行分批而非并发：本方法所在的表生成本身已是表级并发（Semaphore 限流），
     * 表内再叠加并发会把总并发推高到 maxConcurrency² 量级。</p>
     */
    private List<Map<String, Object>> fillRowsWithGapFill(TableMeta table, List<ColumnDef> llmCols,
                                                          int total, String groupHint) {
        String ctx = groupHint == null || groupHint.isBlank()
                ? tableConstraints(table.name())
                : mergePromptContext(groupHint, tableConstraints(table.name()));
        int batchSize = Math.max(1, Math.min(props.batchSize(), total));
        List<Map<String, Object>> all = new ArrayList<>(
                fillBatched(table, llmCols, ctx, total, batchSize));
        // 缺口一：行数不足 → 追加补填
        if (all.size() < total) {
            int gap = total - all.size();
            log.warn("表 `{}` 语义列回填缺口 {} 行（现 {}），补差重填", table.name(), gap, all.size());
            all.addAll(fillBatched(table, llmCols, ctx, gap, batchSize));
        }
        // 缺口二：行数够、但个别行的字段为空（LLM 偶发漏字段/单批长文本被截断）→ 整行重填。
        // 只重填缺的这几行：小批不会触发截断，比整表重来稳；单行缺失就把整表判死太脆。
        List<Integer> holes = blankRows(all, llmCols, total);
        if (!holes.isEmpty()) {
            log.warn("表 `{}` 语义列有 {} 行字段为空，整行补差重填", table.name(), holes.size());
            List<Map<String, Object>> refill = fillBatched(table, llmCols, ctx, holes.size(), batchSize);
            for (int k = 0; k < holes.size() && k < refill.size(); k++) {
                all.set(holes.get(k), refill.get(k));
            }
            holes = blankRows(all, llmCols, total);
        }
        if (all.size() < total) {
            throw new IllegalStateException("表 `" + table.name() + "` 语义列回填不足（"
                    + all.size() + "/" + total + "），标记失败（绝不静默占位）");
        }
        if (!holes.isEmpty()) {
            throw new IllegalStateException("表 `" + table.name() + "` 语义列回填重试后仍有 "
                    + holes.size() + " 行字段为空，标记失败（绝不静默占位）");
        }
        return all;
    }

    /** 字段为空的行下标（缺口二用；行数缺口由调用方另行补齐，此处只看 [0,total) 内） */
    private static List<Integer> blankRows(List<Map<String, Object>> rows, List<ColumnDef> llmCols, int total) {
        List<Integer> holes = new ArrayList<>();
        for (int i = 0; i < Math.min(rows.size(), total); i++) {
            Map<String, Object> row = rows.get(i);
            if (row == null) {
                holes.add(i);
                continue;
            }
            for (ColumnDef c : llmCols) {
                if (row.get(c.name()) == null) {
                    holes.add(i);
                    break;
                }
            }
        }
        return holes;
    }

    /**
     * 分批并发调用 LLM 生成 rows 行的语义列值（Semaphore 限流 maxConcurrency，与 source 表同款）。
     *
     * <p><b>结果必须按批序拼接</b>：调用方 fillLlmColumns 按行下标与回填结果一一对应
     * （llmRows.get(i) → rows.get(i)），顺序错乱会把商品名配到别的商品上——比缺值更危险。</p>
     */
    private List<Map<String, Object>> fillBatched(TableMeta table, List<ColumnDef> llmCols,
                                                  String ctx, int rows, int batchSize) {
        List<int[]> batches = new ArrayList<>();
        for (int offset = 0; offset < rows; offset += batchSize) {
            batches.add(new int[]{offset, Math.min(batchSize, rows - offset)});
        }
        Semaphore semaphore = new Semaphore(Math.max(1, props.maxConcurrency()));
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<CompletableFuture<List<Map<String, Object>>>> futures = new ArrayList<>();
            for (int[] batch : batches) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        semaphore.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    try {
                        return llm.generateRows(table.name(), llmCols, batch[1], ctx);
                    } finally {
                        semaphore.release();
                    }
                }, pool));
            }
            List<Map<String, Object>> all = new ArrayList<>(rows);
            for (CompletableFuture<List<Map<String, Object>>> f : futures) {
                List<Map<String, Object>> part = f.join();
                if (part != null) all.addAll(part);
            }
            return all;
        } finally {
            pool.shutdown();
        }
    }

    /**
     * 分类 LLM 语义列（rule 优先）：先判定"能确定性生成的列"（plan 工具列 + 铁律 ENUM/日期 +
     * pattern 字符串 + 数值枚举），排除后其余才是 LLM。防线内置：语义字符串（非 pattern）即使
     * plan 判为工具也强制回 LLM（RuleDataGenerator 只生成 pattern 字符串，防占位垃圾）。
     */
    private List<ColumnDef> classifyLlmColumns(TableMeta table, List<String> fkCols) {
        Set<String> ruleNames = ruleColumnNames(table, fkCols);
        List<ColumnDef> llmCols = new ArrayList<>();
        for (ColumnDef c : table.columns()) {
            if (c.primaryKey() || fkCols.contains(c.name())) continue;   // 铁律：PK/FK 由骨架/预分配注入
            if (ruleNames.contains(c.name())) continue;                  // rule 优先：能确定性生成的排除
            llmCols.add(c);                                              // 其余才是 LLM 语义列
        }
        return llmCols;
    }

    /** rule 优先判定：plan 工具列 ∪ ColumnClassifier.RULE 列，减去语义字符串（防线） */
    private Set<String> ruleColumnNames(TableMeta table, List<String> fkCols) {
        Set<String> names = new HashSet<>();
        // ① plan 工具列（mode != llm）：分析 agent 工具感知决策的工具（计划随本次运行上下文传入）
        if (expansion != null) {
            GenerationPlan.TablePlan tp = context.plan().tablePlan(table.name());
            if (tp != null) {
                for (GenerationPlan.ColumnPlan cp : tp.columns()) {
                    if (!"llm".equals(cp.mode())) names.add(cp.column());
                }
            }
        }
        // ② ColumnClassifier 判 RULE 的列（显式 ENUM / 日期 / 数值枚举 / pattern 字符串）
        for (ColumnClassifier.ColumnClassification cc : ColumnClassifier.hybridClassify(table.columns(), fkCols)) {
            if (cc.category() == ColumnClassifier.Category.RULE) names.add(cc.column().name());
        }
        // ③ 防线：语义字符串（非 pattern）即使被判 rule 也排除——RuleDataGenerator 生成不了 → 必须 LLM
        for (ColumnDef c : table.columns()) {
            if (isSemanticString(c)) names.remove(c.name());
        }
        return names;
    }

    /**
     * 语义字符串列：VARCHAR/CHAR/TEXT 且非 pattern 格式列。RuleDataGenerator 只生成 pattern 字符串
     * （phone/email/url/身份证/IP/QQ 等），其余字符串必须 LLM——误入规则列会产占位垃圾。
     */
    private static boolean isSemanticString(ColumnDef c) {
        String type = c.normalizedType();
        if (!type.equals("VARCHAR") && !type.equals("CHAR") && !type.equals("TEXT")) return false;
        return !ColumnClassifier.isPatternString(c);
    }

    /** 该表相关前置约束文本（generationRules → prompt 段落，决策 #21；无则空串） */
    private String tableConstraints(String tableName) {
        if (rules == null || rules.generationRules() == null) return "";
        return PromptBuilder.buildConstraintsSection(tableName, rules.generationRules());
    }

    /** 拼接父表上下文 + 前置约束；两者皆无返回 null（调用方传 null 兼容旧行为） */
    private static String mergePromptContext(String parentContext, String constraints) {
        if (constraints == null || constraints.isBlank()) return parentContext;
        if (parentContext == null || parentContext.isBlank()) return constraints;
        return parentContext + "\n" + constraints;
    }

    /** 记录已完成表的列值索引（子表 FK 上下文 prompt 用） */
    private void recordCompletedData(TableMeta table, GeneratedData data) {
        Map<String, List<Object>> record = new LinkedHashMap<>();
        for (ColumnDef col : table.columns()) {
            List<Object> values = new ArrayList<>(data.rowCount());
            for (DataRow row : data.rows()) {
                values.add(row.values().get(col.name()));
            }
            record.put(col.name(), values);
        }
        completedData.put(table.name(), record);
    }
}
