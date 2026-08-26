package com.dataforge.cli;

import com.dataforge.agent.AnalysisAgent;
import com.dataforge.agent.CorrectionAgent;
import com.dataforge.agent.DefaultAnalysisAgent;
import com.dataforge.agent.DefaultGenerationAgent;
import com.dataforge.agent.GenerationAgent;
import com.dataforge.config.GeneratorProperties;
import com.dataforge.correction.CorrectionPipeline;
import com.dataforge.db.JdbcDataSource;
import com.dataforge.db.MysqlJdbcDataSource;
import com.dataforge.db.StagingManager;
import com.dataforge.llm.DataLlm;
import com.dataforge.model.ColumnDef;
import com.dataforge.model.DataRow;
import com.dataforge.model.FixCommand;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.IncrementalConfig;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.SourceTag;
import com.dataforge.model.TableMeta;
import com.dataforge.model.ValidationReport;
import com.dataforge.parser.DdlParser;
import com.dataforge.report.RunReport;
import com.dataforge.validation.DataValidator;
import com.dataforge.validation.Scorer;
import com.dataforge.validation.SqlValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CLI 入口：读 DDL → 引用闭包 → 生成管线 → 对账 → 校验 → 汇总输出。
 *
 * <pre>
 * 用法：
 *   java -jar dataforge.jar --ddl &lt;schema.sql&gt; --rows user=20,product=20,sku=80,orders=100 \
 *       [--seed 42] [--batch-size 100] [--max-concurrency 8] [--pk sequential|snowflake] \
 *       [--min-parent-rows 5] [--cache-dir .dataforge/cache] [--no-cache] [--mock-llm] [--out &lt;dir&gt;]
 * </pre>
 */
@Component
public class DataForgeCli implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataForgeCli.class);

    private final DdlParser ddlParser;
    private final DataLlm llm;
    private final GeneratorProperties defaults;
    private final DefaultGenerationAgent generationAgent;
    private final StagingManager stagingManager;
    private final SqlValidator sqlValidator;
    private final CorrectionAgent correctionAgent;
    private final CorrectionPipeline correctionPipeline;
    private final DefaultAnalysisAgent analysisAgent;
    private final Scorer scorer;
    private final JdbcDataSource jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DataForgeCli(DdlParser ddlParser, DataLlm llm, GeneratorProperties defaults,
                        DefaultGenerationAgent generationAgent,
                        StagingManager stagingManager, SqlValidator sqlValidator,
                        CorrectionAgent correctionAgent, CorrectionPipeline correctionPipeline,
                        DefaultAnalysisAgent analysisAgent, Scorer scorer, JdbcDataSource jdbc) {
        this.ddlParser = ddlParser;
        this.llm = llm;
        this.defaults = defaults;
        this.generationAgent = generationAgent;
        this.stagingManager = stagingManager;
        this.sqlValidator = sqlValidator;
        this.correctionAgent = correctionAgent;
        this.correctionPipeline = correctionPipeline;
        this.analysisAgent = analysisAgent;
        this.scorer = scorer;
        this.jdbc = jdbc;
    }

    /** CLI 参数（简单手写解析，不引第三方） */
    record CliOptions(
            String ddlPath,
            Map<String, Integer> rows,
            GeneratorProperties props,
            boolean noCache,
            boolean noExpansion,
            String outDir,
            String stagingSchema,
            boolean correct,
            boolean fixOnly,
            boolean pipeline,
            boolean incremental,
            String analyzeSchema,
            boolean score,
            int maxIterations
    ) {
        static CliOptions parse(String[] args, GeneratorProperties defaults) {
            Map<String, String> kv = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                if (args[i].startsWith("--")) {
                    String key = args[i];
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        kv.put(key, args[++i]);
                    } else {
                        kv.put(key, "true");
                    }
                }
            }
            String ddlPath = kv.get("--ddl");
            String analyzeSchema = kv.get("--analyze");
            if (ddlPath == null && analyzeSchema == null) {
                throw new IllegalArgumentException("缺少必填参数 --ddl <schema.sql> 或 --analyze <schema>");
            }
            String rowsStr = kv.get("--rows");
            if (rowsStr == null && analyzeSchema == null) {
                throw new IllegalArgumentException("缺少必填参数 --rows user=20,product=20,...");
            }
            Map<String, Integer> rows = rowsStr == null ? Map.of() : parseRows(rowsStr);

            long seed = parseLong(kv, "--seed", defaults.seed());
            int batchSize = parseInt(kv, "--batch-size", defaults.batchSize());
            int maxConcurrency = parseInt(kv, "--max-concurrency", defaults.maxConcurrency());
            int minParentRows = parseInt(kv, "--min-parent-rows", defaults.minParentRows());
            String pkStrategy = kv.getOrDefault("--pk", defaults.pkStrategy());
            if (!pkStrategy.equals("sequential") && !pkStrategy.equals("snowflake")) {
                throw new IllegalArgumentException("--pk 仅支持 sequential / snowflake");
            }
            String cacheDir = kv.getOrDefault("--cache-dir", defaults.cacheDir());
            boolean noCache = kv.containsKey("--no-cache");
            boolean noExpansion = kv.containsKey("--no-expansion");
            boolean correct = kv.containsKey("--correct");
            boolean fixOnly = kv.containsKey("--fix-only");
            boolean pipeline = kv.containsKey("--pipeline");
            boolean incremental = kv.containsKey("--incremental");
            boolean score = kv.containsKey("--score");
            int maxIterations = parseInt(kv, "--max-iterations", defaults.maxIterations());

            GeneratorProperties props = new GeneratorProperties(
                    defaults.mockLlm(), seed, batchSize, maxConcurrency, pkStrategy,
                    cacheDir, minParentRows, maxIterations);
            return new CliOptions(ddlPath, rows, props, noCache, noExpansion,
                    kv.get("--out"), kv.get("--staging"), correct, fixOnly,
                    pipeline, incremental, analyzeSchema, score, maxIterations);
        }

        private static Map<String, Integer> parseRows(String rowsStr) {
            Map<String, Integer> rows = new LinkedHashMap<>();
            for (String part : rowsStr.split(",")) {
                String[] kv = part.trim().split("=");
                if (kv.length != 2) {
                    throw new IllegalArgumentException("--rows 格式错误: " + part + "（应为 表名=行数）");
                }
                int count = Integer.parseInt(kv[1].trim());
                if (count <= 0) {
                    throw new IllegalArgumentException("--rows 行数必须 > 0: " + part);
                }
                rows.put(kv[0].trim(), count);
            }
            return rows;
        }

        private static long parseLong(Map<String, String> kv, String key, long fallback) {
            return kv.containsKey(key) ? Long.parseLong(kv.get(key)) : fallback;
        }

        private static int parseInt(Map<String, String> kv, String key, int fallback) {
            return kv.containsKey(key) ? Integer.parseInt(kv.get(key)) : fallback;
        }
    }

    @Override
    public void run(String... args) {
        // P7 Web 模式：CLI 跳过，Web 服务器处理请求
        if (java.util.Arrays.asList(args).contains("--web")) {
            return;
        }
        try {
            execute(args);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误: " + e.getMessage());
            System.err.println("用法: --ddl <sql> --rows user=20,... [--seed 42] [--batch-size 100]"
                    + " [--max-concurrency 8] [--pk sequential|snowflake] [--min-parent-rows 5]"
                    + " [--no-cache] [--mock-llm] [--out <dir>]"
                    + " [--staging <schema>] [--correct] [--fix-only] [--pipeline] [--incremental]"
                    + " [--max-iterations 10]");
            System.exit(2);
        } catch (Exception e) {
            log.error("生成失败", e);
            System.err.println("生成失败: " + e.getMessage());
            System.exit(1);
        }
    }

    void execute(String[] args) throws Exception {
        CliOptions opts = CliOptions.parse(args, defaults);

        // 0. P6 分析 agent：读库结构（或 --ddl 文件）→ 提炼 project_rules + 生成计划 + 确认清单
        if (opts.analyzeSchema() != null) {
            AnalysisAgent.AnalysisResult result;
            if (opts.ddlPath() != null) {
                // 新域验证：从 DDL 文件解析表结构分析（不连库）
                List<TableMeta> ddlTables = ddlParser.parse(Path.of(opts.ddlPath())).tables();
                result = analysisAgent.analyzeTables(ddlTables);
            } else {
                result = analysisAgent.analyze(
                        new AnalysisAgent.AnalysisRequest(null, opts.analyzeSchema(), List.of()));
            }
            printAnalysis(opts.analyzeSchema(), result);
            return;
        }

        // 1. 解析 DDL
        DdlParser.DdlParseResult parsed = ddlParser.parse(Path.of(opts.ddlPath()));
        if (parsed.tables().isEmpty()) {
            throw new IllegalStateException("DDL 未解析出任何表: " + String.join("; ", parsed.errors()));
        }
        for (String err : parsed.errors()) {
            log.warn("DDL 解析提示: {}", err);
        }
        Map<String, TableMeta> allTables = parsed.tables().stream()
                .collect(Collectors.toMap(TableMeta::name, t -> t));

        // 2. 引用闭包：指定表 ∪ 沿 FK 的必需父表（未指定行数用 min-parent-rows）
        for (String table : opts.rows().keySet()) {
            if (!allTables.containsKey(table)) {
                throw new IllegalArgumentException("DDL 中不存在表: " + table);
            }
        }
        Set<String> closure = new LinkedHashSet<>(opts.rows().keySet());
        Deque<String> queue = new ArrayDeque<>(opts.rows().keySet());
        while (!queue.isEmpty()) {
            TableMeta meta = allTables.get(queue.poll());
            if (meta == null) continue;
            for (var fk : meta.foreignKeys()) {
                if (allTables.containsKey(fk.refTable()) && closure.add(fk.refTable())) {
                    queue.add(fk.refTable());
                }
            }
        }
        Map<String, Integer> rowCounts = new LinkedHashMap<>();
        for (String table : closure) {
            rowCounts.put(table, opts.rows().getOrDefault(table, opts.props().minParentRows()));
        }
        List<TableMeta> genTables = parsed.tables().stream()
                .filter(t -> closure.contains(t.name())).toList();
        if (opts.props().minParentRows() > 0 && closure.size() > opts.rows().size()) {
            List<String> autoAdded = closure.stream().filter(t -> !opts.rows().containsKey(t)).toList();
            log.info("引用闭包自动纳入父表（各 {} 行）: {}", opts.props().minParentRows(), autoAdded);
        }

        // 3. 生成请求（生成 / 流水线共用）
        GenerationAgent.GenerationRequest request = new GenerationAgent.GenerationRequest(
                genTables, currentRules(), rowCounts, IncrementalConfig.full(),
                opts.props().seed(), opts.props().batchSize(), opts.props().maxConcurrency());

        // 3.1 加载分析产出的生成计划（若有）→ 配置驱动生成（通用化）
        loadPlan();

        // 3.5 P4 流水线修正：生成+落库+修正一体（表完成事件触发，小表修正不阻塞大表）
        if (opts.stagingSchema() != null && opts.correct() && opts.pipeline() && !opts.fixOnly()) {
            CorrectionPipeline.PipelineOutcome outcome = correctionPipeline.run(
                    opts.stagingSchema(), genTables, currentRules(),
                    request, !opts.noCache(), !opts.noExpansion());
            printPipelineSummary(opts, outcome);
            return;
        }

        // 3.6 P5 增量生成：现有数据保留，追加不冲突不误伤（起始 ID=MAX+1 + 现有池引用 + 作用域修正）
        if (opts.incremental()) {
            if (opts.stagingSchema() == null || opts.stagingSchema().isBlank()) {
                throw new IllegalArgumentException("--incremental 需要 --staging <schema>（读现有数据当 baseline）");
            }
            // 增量只生成用户指定的表（闭包父表 rowCount=0 → 引用现有）
            GenerationAgent.GenerationRequest incReq = new GenerationAgent.GenerationRequest(
                    genTables, currentRules(), new LinkedHashMap<>(opts.rows()),
                    IncrementalConfig.full(), opts.props().seed(),
                    opts.props().batchSize(), opts.props().maxConcurrency());
            printIncremental(opts.stagingSchema(), genTables, incReq, opts);
            return;
        }

        // 3.7 P7 软评分：staging 生成 vs 真实库分布对比（--score）
        if (opts.score()) {
            if (opts.stagingSchema() == null || opts.stagingSchema().isBlank()) {
                throw new IllegalArgumentException("--score 需要 --staging <schema>（读现有 staging 生成数据）");
            }
            printScore(opts.stagingSchema(), genTables);
            return;
        }

        // 4. 生成 agent（唯一对外入口）：PK → FK → DAG 生成（5 优化）→ 跨表对账
        GenerationAgent.GenerationResult result = generationAgent.generate(
                request, !opts.noCache(), !opts.noExpansion());

        // 4. 确定性校验（FK 悬挂 / 唯一冲突 / 金额对账）
        DataValidator.ValidationSummary summary = DataValidator.validate(
                genTables, result.data(), Set.copyOf(result.failedTables()));

        // 5. 汇总输出
        printSummary(opts, result, summary, result.elapsedMs());

        // 6. 可选导出
        if (opts.outDir() != null) {
            exportData(opts.outDir(), result.data());
        }

        // 7. --staging：落 staging 工作库 + SQL 校验；--correct 时进入修正 agent 收敛循环
        CorrectionOutcome outcome = null;
        if (opts.stagingSchema() != null && !opts.stagingSchema().isBlank()) {
            if (opts.correct()) {
                if (opts.fixOnly()) {
                    // 只修正：读现有 staging → 校验 → 修正（不重建，保留库内数据）
                    Map<String, GeneratedData> existing =
                            stagingManager.read(opts.stagingSchema(), genTables);
                    outcome = printCorrection(opts.stagingSchema(), genTables, existing,
                            opts.maxIterations(), false);
                } else {
                    outcome = printCorrection(opts.stagingSchema(), genTables, result.data(),
                            opts.maxIterations(), true);
                }
            } else {
                printSqlValidation(opts.stagingSchema(), genTables);
            }
        }

        // 8. 运行报告（可观测性）：聚合生成/校验/修正指标 → 统一 JSON + 落盘 run-reports/
        RunReport report = new RunReport()
                .mode(outcome != null ? "generate+correct" : "generate")
                .generation(result);
        if (outcome != null) {
            report.validation(outcome.initial()).correction(outcome.fix());
        }
        report.printAndSave();
    }

    /** P4 流水线修正汇总（表完成事件触发，边生成边落库边修正） */
    private void printPipelineSummary(CliOptions opts, CorrectionPipeline.PipelineOutcome outcome) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║                 DataForge P4 流水线修正结果                        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.printf("模式: mock=%s | seed: %d | 修正表数: %d | 修正轮数: %d | 修正指令: %d%n",
                llm.isMock(), opts.props().seed(),
                outcome.tablesFixed(), outcome.fixRounds(), outcome.fixCommands());
        System.out.println("表完成事件触发修正：小表修正不阻塞大表生成（边生成边落库边修正）");
        System.out.println("最终数据已从 staging 读回（修正列标 CORRECTED 血缘）");
    }

    /**
     * P5 增量流程：读现有 baseline → 增量生成（起始 MAX+1 + 现有池引用）→
     * 重建 staging 完整快照（现有保留 + 新增追加）→ 跨现有校验 → 作用域修正。
     */
    private void printIncremental(String schemaName, List<TableMeta> tables,
                                  GenerationAgent.GenerationRequest request, CliOptions opts) {
        System.out.println();
        System.out.println("=== P5 增量生成（现有数据保留，追加不冲突不误伤）===");
        // 1. baseline 现有数据
        Map<String, GeneratedData> baseline = stagingManager.read(schemaName, tables);
        int existingRows = baseline.values().stream().mapToInt(GeneratedData::rowCount).sum();
        System.out.printf("现有 baseline: %d 张表 / %d 行%n", baseline.size(), existingRows);
        if (baseline.isEmpty()) {
            System.out.println("⚠️ staging 无现有数据，增量退化为全量（请先跑全量生成落库）");
        }
        // 2. 增量生成（起始 ID=MAX+1，FK 跨现有池，展开器跨现有引用）
        GenerationAgent.GenerationResult result =
                generationAgent.generateIncremental(request, baseline, !opts.noCache(), !opts.noExpansion());
        // 3. 重建 staging 为完整快照（现有行保留 + 新增追加）
        stagingManager.drop(schemaName);
        stagingManager.create(schemaName, tables);
        stagingManager.load(schemaName, result.data());
        // 4. 跨现有校验
        ProjectRules rules = currentRules();
        ValidationReport initial = sqlValidator.validate(tables, rules, schemaName);
        System.out.printf("跨现有校验: %d 个问题分桶 / %d 行问题%n",
                initial.buckets().size(), initial.totalErrors());
        for (ValidationReport.Bucket b : initial.buckets()) {
            System.out.printf("  [%s] %s.%s: %d 行 (%.1f%%) — %s%n",
                    b.errorType(), b.table(), b.column(), b.count(), b.ratio() * 100, b.rootCause());
        }
        // 5. 修正（作用域限定新区间：id > 现有最大 id，不触碰现有数据）
        Long scopeMinId = scopeMinIdOf(baseline);
        CorrectionAgent.CorrectionResult fix = correctionAgent.correct(
                new CorrectionAgent.CorrectionRequest(tables, result.data(), initial, rules,
                        schemaName, scopeMinId));
        // 6. 报告
        System.out.println();
        System.out.println("=== P5 增量修正报告 ===");
        System.out.printf("新增表: %d 张 | 修正 %d 轮 / %d 条 | 作用域 id > %s | 收敛: %s%n",
                result.actualCounts().size(), fix.iterations(), fix.appliedFixes().size(),
                scopeMinId == null ? "不限" : scopeMinId, fix.converged() ? "✅" : "❌");
        if (fix.finalReport().totalErrors() == 0) {
            System.out.println("✅ 跨现有校验 + 作用域修正后硬错误为 0（现有数据保留，未误伤）");
        } else {
            for (ValidationReport.Bucket b : fix.finalReport().buckets()) {
                System.out.printf("  残余 [%s] %s.%s: %d 行%n",
                        b.errorType(), b.table(), b.column(), b.count());
            }
        }
    }

    /** 增量修正作用域下界 = 各表现有 MAX(id) 的最小值 + 1（只修正新增区间，不触碰现有） */
    private static Long scopeMinIdOf(Map<String, GeneratedData> baseline) {
        long minStart = Long.MAX_VALUE;
        boolean any = false;
        for (GeneratedData data : baseline.values()) {
            long maxId = data.rows().stream()
                    .map(r -> r.values().get("id")).filter(Objects::nonNull)
                    .mapToLong(v -> ((Number) v).longValue()).max().orElse(0);
            if (data.rowCount() > 0) {
                any = true;
                minStart = Math.min(minStart, maxId + 1);
            }
        }
        return any ? minStart : null;
    }

    /** 加载分析产出的生成计划（generation-plan.json）→ 配置驱动生成（通用化） */
    private void loadPlan() {
        try {
            Path file = Path.of("generation-plan.json");
            if (Files.exists(file)) {
                com.dataforge.model.GenerationPlan plan = objectMapper.readValue(
                        file.toFile(), com.dataforge.model.GenerationPlan.class);
                generationAgent.setPlan(plan);
                log.info("加载生成计划 generation-plan.json（{} 张表，配置驱动生成）", plan.tables().size());
            }
        } catch (Exception e) {
            log.warn("generation-plan.json 加载失败（回退内置模板）: {}", e.getMessage());
        }
    }

    /** 当前生效规则：优先分析 agent 落盘的 project_rules.json，否则内置默认 */
    private ProjectRules currentRules() {
        try {
            Path file = Path.of("project_rules.json");
            if (Files.exists(file)) {
                ProjectRules loaded = objectMapper.readValue(file.toFile(), ProjectRules.class);
                log.info("加载分析规则 project_rules.json（correction {} 条）", loaded.correctionRules().size());
                return loaded;
            }
        } catch (Exception e) {
            log.warn("project_rules.json 读取失败（回退内置默认）: {}", e.getMessage());
        }
        return ProjectRules.withDefaultCorrectionRules();
    }

    /** P6 分析报告：提炼规则清单（人工检查点）+ 置信度 + 行数推荐 + 落盘 */
    private void printAnalysis(String schemaName, AnalysisAgent.AnalysisResult result) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║              DataForge P6 分析 agent（project_rules）           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.printf("库: %s | 提炼规则 %d 条 | correction 可执行 %d 条%n",
                schemaName, result.rules().generationRules().size(),
                result.rules().correctionRules().size());
        System.out.println("指纹: schema=" + result.fingerprint().schemaFingerprint()
                + " rules=" + result.fingerprint().rulesFileHash());
        System.out.println();
        System.out.println("规则清单（置信度 <0.7 优先人工确认）:");
        for (ProjectRules.Rule r : result.rules().generationRules()) {
            String flag = r.confidence() < 0.7 ? " ⚠️ 低置信度" : "";
            System.out.printf("  [%s] %s.%s — %s (置信度 %.1f)%s%n",
                    r.type(), r.table(), r.column(), r.expression(), r.confidence(), flag);
            if (!r.params().isEmpty()) {
                System.out.println("      params=" + r.params());
            }
        }
        System.out.println();
        System.out.println("后置修正规则（校验/修正类，含 CUSTOM_SQL 自定义校验）:");
        for (ProjectRules.Rule r : result.rules().correctionRules()) {
            String flag = r.confidence() < 0.7 ? " ⚠️ 低置信度" : "";
            System.out.printf("  [%s] %s.%s — %s (置信度 %.1f)%s%n",
                    r.type(), r.table(), r.column(), r.expression(), r.confidence(), flag);
            if (!r.params().isEmpty()) {
                System.out.println("      params=" + r.params());
            }
        }
        System.out.println();
        System.out.println("行数推荐（按关联推导）: " + result.rowRecommend());

        // 生成计划（通用化配置驱动：表分类 + 列模式，人工确认）
        System.out.println();
        System.out.println("生成计划（配置驱动，人工确认后可改 generation-plan.json）:");
        for (com.dataforge.model.GenerationPlan.TablePlan tp : result.plan().tables()) {
            System.out.printf("  %s [%s]%s", tp.table(), tp.builder(),
                    tp.parent() != null ? " ← " + tp.parent() : "");
            List<String> modes = tp.columns().stream()
                    .map(c -> c.column() + "=" + c.mode()).toList();
            System.out.println(modes.isEmpty() ? "" : " | 列: " + String.join(", ", modes));
        }
        try {
            Path planFile = Path.of("generation-plan.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(planFile.toFile(), result.plan());
            System.out.println();
            System.out.println("✅ 生成计划已落盘: " + planFile.toAbsolutePath()
                    + "（后续生成自动加载，配置驱动零 Java 改动）");
        } catch (Exception e) {
            System.err.println("生成计划落盘失败: " + e.getMessage());
        }
        try {
            Path file = Path.of("project_rules.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), result.rules());
            System.out.println("✅ project_rules 已落盘: " + file.toAbsolutePath());
            System.out.println("后续 --staging / --correct / --incremental 自动加载分析规则（替代内置默认）");
        } catch (Exception e) {
            System.err.println("project_rules 落盘失败: " + e.getMessage());
        }
        new RunReport().mode("analyze").analysis(result).printAndSave();
    }

    /** P7 软评分：读 staging 生成数据 vs 真实库参考分布 → 逐列 KS/JS 距离报告 */
    private void printScore(String schemaName, List<TableMeta> tables) {
        System.out.println();
        System.out.println("=== P7 软评分（统计距离：生成 vs 真实库分布对比）===");
        Map<String, GeneratedData> generated = stagingManager.read(schemaName, tables);
        Map<String, GeneratedData> reference = readRealTables(schemaName, tables);
        Scorer.ScoreReport report = scorer.score(generated, reference);
        System.out.printf("总体分: %.3f（0-1，越高越接近真实分布）%n", report.overall());
        for (String note : report.notes()) {
            System.out.println("  " + note);
        }
        for (Scorer.TableScore ts : report.tables().values()) {
            System.out.printf("表 %s: %.3f%n", ts.table(), ts.score());
            for (Scorer.ColumnScore cs : ts.columns()) {
                System.out.printf("  %s.%s [%s]: 距离=%.3f 分=%.3f%n",
                        ts.table(), cs.column(), cs.method(), cs.distance(), cs.score());
            }
        }
        // 语义抽样（LLM 打分，mock 确定性 / 真实调 LLM）
        if (!generated.isEmpty()) {
            GeneratedData sample = generated.values().iterator().next();
            List<Map<String, Object>> samples = sample.rows().stream()
                    .limit(5).map(DataRow::values).toList();
            double semantic = llm.scoreSamples(sample.tableName(), samples);
            if (semantic >= 0) {
                System.out.printf("语义抽样（%s，前 5 行）: %.2f%n", sample.tableName(), semantic);
            }
        }
        new RunReport().mode("score").scoring(report).printAndSave();
    }


    /** 读真实库表数据（软评分参考分布） */
    private Map<String, GeneratedData> readRealTables(String schemaName, List<TableMeta> tables) {
        Map<String, GeneratedData> real = new LinkedHashMap<>();
        for (TableMeta t : tables) {
            String sql = "SELECT * FROM " + MysqlJdbcDataSource.quote(schemaName) + "."
                    + MysqlJdbcDataSource.quote(t.name());
            List<Map<String, Object>> rows = jdbc.query(sql);
            List<DataRow> dataRows = rows.stream().map(r -> {
                Map<String, SourceTag> lineage = new LinkedHashMap<>();
                for (ColumnDef c : t.columns()) lineage.put(c.name(), SourceTag.SEED);
                return new DataRow(new LinkedHashMap<>(r), lineage);
            }).toList();
            real.put(t.name(), new GeneratedData(t.name(), t.columns(), dataRows));
        }
        return real;
    }

    /** 修正流程产物（RunReport 组装用）：首轮校验 + 收敛结果 */
    private record CorrectionOutcome(ValidationReport initial, CorrectionAgent.CorrectionResult fix) {
    }

    /**
     * 修正 agent 流程：落库 → 首轮校验 → ReAct 收敛循环（决策→id 锚定应用→再校验）
     * → 收敛报告（含回归回滚标记 + 修正明细 + 最终报告）。
     */
    private CorrectionOutcome printCorrection(String schemaName, List<TableMeta> tables,
                                              Map<String, GeneratedData> data, int maxIterations,
                                              boolean recreate) {
        System.out.println();
        System.out.println("=== 修正 agent：ReAct 收敛循环（staging 工作库）===");
        if (recreate) {
            stagingManager.drop(schemaName);   // 回滚 = 重建工作区
            stagingManager.create(schemaName, tables);
            stagingManager.load(schemaName, data);
        } else {
            System.out.println("只修正模式：读现有 staging 数据，不重建（data 为回滚快照）");
        }

        ValidationReport initial = sqlValidator.validate(tables, currentRules(), schemaName);
        System.out.printf("首轮校验: %d 个问题分桶 / %d 行问题%n", initial.buckets().size(), initial.totalErrors());
        for (ValidationReport.Bucket b : initial.buckets()) {
            System.out.printf("  [%s] %s.%s: %d 行 (%.1f%%) — %s%n",
                    b.errorType(), b.table(), b.column(), b.count(), b.ratio() * 100, b.rootCause());
        }
        if (initial.totalErrors() == 0) {
            System.out.println("✅ 首轮校验全通过，无需修正");
            return new CorrectionOutcome(initial, null);
        }

        CorrectionAgent.CorrectionResult fix = correctionAgent.correct(
                new CorrectionAgent.CorrectionRequest(tables, data, initial,
                        currentRules(), schemaName, null));

        System.out.println();
        System.out.println("=== 修正收敛报告 ===");
        System.out.printf("循环轮数: %d | 收敛: %s | 回归回滚: %s | 修正条数: %d%n",
                fix.iterations(), fix.converged() ? "✅" : "❌",
                fix.rolledBack() ? "⚠️ 是（已重建工作区）" : "否", fix.appliedFixes().size());
        if (fix.rolledBack()) {
            System.out.println("⚠️ 修正引入首轮清单之外的新错误（回归）→ 已回滚到初始数据，请核查数据/规则");
        } else if (!fix.converged()) {
            System.out.println("⚠️ 未完全收敛（达到迭代上限或通过率未上升），残余问题上报人工");
        }
        if (!fix.appliedFixes().isEmpty()) {
            System.out.println("修正明细（id 锚定最小干预）:");
            int shown = 0;
            for (FixCommand f : fix.appliedFixes()) {
                if (shown++ >= 20) {
                    System.out.println("  ... 共 " + fix.appliedFixes().size() + " 条");
                    break;
                }
                System.out.printf("  %s #%d → %s（%s）%n", f.table(), f.id(), f.fixes(), f.reason());
            }
        }

        System.out.println();
        System.out.println("=== 最终校验报告（收敛后）===");
        if (fix.finalReport().totalErrors() == 0) {
            System.out.println("✅ 硬错误已收敛为 0");
        } else {
            for (ValidationReport.Bucket b : fix.finalReport().buckets()) {
                System.out.printf("  [%s] %s.%s: %d 行 (%.1f%%) — %s%n",
                        b.errorType(), b.table(), b.column(), b.count(), b.ratio() * 100, b.rootCause());
            }
        }
        return new CorrectionOutcome(initial, fix);
    }

    /** 落 staging 库并跑 SQL 校验（先清旧 → 建库建表 → 批量导入 → Detect/Aggregate 报告） */
    private void printSqlValidation(String schemaName, List<TableMeta> tables) {
        System.out.println();
        System.out.println("=== 落 staging 工作库 + SQL 校验（确定性 JDBC 通道）===");
        stagingManager.drop(schemaName);       // 回滚 = 重建工作区
        stagingManager.create(schemaName, tables);
        stagingManager.load(schemaName, generationAgent.lastData());
        ValidationReport report = sqlValidator.validate(tables, currentRules(), schemaName);

        System.out.printf("staging 库: %s | 问题分桶 %d 个 | 问题行 %d | 校验耗时 %dms%n",
                stagingManager.stagingSchema(schemaName), report.buckets().size(),
                report.totalErrors(), report.elapsedMs());
        if (report.buckets().isEmpty()) {
            System.out.println("✅ SQL 校验全通过（FK 无悬挂 / 唯一无冲突 / 金额对账一致）");
        } else {
            for (ValidationReport.Bucket b : report.buckets()) {
                System.out.printf("  [%s] %s.%s: %d 行 (%.1f%%) — %s%n",
                        b.errorType(), b.table(), b.column(), b.count(), b.ratio() * 100, b.rootCause());
            }
        }
        new RunReport().mode("validate").validation(report).printAndSave();
    }

    private void printSummary(CliOptions opts, GenerationAgent.GenerationResult result,
                              DataValidator.ValidationSummary summary, long totalElapsed) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║                     DataForge P0 生成结果                         ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.printf("模式: %-6s | 生成: %s | seed: %d | PK: %s | batch: %d | 并发: %d | 缓存: %s%n",
                llm.isMock() ? "mock" : "deepseek",
                opts.noExpansion() ? "纯LLM(P0)" : "两阶段(P1)",
                opts.props().seed(), opts.props().pkStrategy(),
                opts.props().batchSize(), opts.props().maxConcurrency(),
                opts.noCache() ? "禁用" : "启用");
        System.out.println();
        System.out.printf("%-22s %6s %6s %5s %5s %5s %6s %8s%n",
                "表", "目标", "实际", "LLM批", "降批", "补差", "缓存", "耗时");
        System.out.println("-".repeat(72));
        for (String table : result.tableStats().keySet()) {
            GenerationAgent.TableStat s = result.tableStats().get(table);
            System.out.printf("%-22s %6d %6d %5d %5d %5d %6s %7dms%n",
                    table, s.target(), s.actual(),
                    s.llmBatches(), s.downshifts(), s.gapFills(),
                    s.cacheHit() ? "命中" : "-", s.elapsedMs());
        }
        if (!result.failedTables().isEmpty()) {
            System.out.println("-".repeat(72));
            System.out.println("失败表（失败隔离，不影响其余）: " + result.failedTables());
        }
        System.out.println("-".repeat(72));
        System.out.printf("合计: %d 张表 / %d 行 | 总耗时 %d ms%n",
                result.tableStats().size(),
                result.actualCounts().values().stream().mapToInt(Integer::intValue).sum(),
                totalElapsed);
        System.out.println();

        // 校验汇总
        System.out.println("=== 确定性校验（规则引擎，非 LLM）===");
        System.out.printf("FK 悬挂: %d %s%n", summary.fkOrphanCount(),
                summary.fkOrphanCount() == 0 ? "✓" : "✗");
        System.out.printf("唯一键冲突: %d %s%n", summary.uniqueConflictCount(),
                summary.uniqueConflictCount() == 0 ? "✓" : "✗");
        if (summary.amountChecked()) {
            System.out.printf("金额对账: %d 差异 %s%n", summary.amountMismatchCount(),
                    summary.amountMismatchCount() == 0 ? "✓" : "✗");
        } else {
            System.out.println("金额对账: 未核对（需同时指定 orders 与 order_item，如 --rows ...orders=100,order_item=250）");
        }
        for (String note : summary.notes()) {
            System.out.println("  " + note);
        }
        if (summary.clean() && result.failedTables().isEmpty()) {
            System.out.println();
            if (summary.amountChecked()) {
                System.out.println("✅ 全部校验通过：FK 无悬挂、唯一键无冲突、金额对账一致");
            } else {
                System.out.println("✅ 校验通过：FK 无悬挂、唯一键无冲突（金额对账未核对）");
            }
        } else {
            System.out.println();
            System.out.println("⚠️ 存在校验问题或失败表（P3 修正 agent 处理）");
        }
    }

    private void exportData(String outDir, Map<String, GeneratedData> data) throws Exception {
        Path dir = Path.of(outDir);
        Files.createDirectories(dir);
        for (Map.Entry<String, GeneratedData> e : data.entrySet()) {
            List<Map<String, Object>> rows = e.getValue().rows().stream()
                    .map(r -> r.values()).toList();
            Path file = dir.resolve(e.getKey() + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), rows);
        }
        System.out.println("数据已导出: " + dir.toAbsolutePath());
    }
}
