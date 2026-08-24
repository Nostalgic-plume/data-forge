package com.dataforge.correction;

import com.dataforge.agent.CorrectionAgent;
import com.dataforge.agent.DefaultGenerationAgent;
import com.dataforge.agent.GenerationAgent;
import com.dataforge.config.GeneratorProperties;
import com.dataforge.db.StagingManager;
import com.dataforge.model.ForeignKeyDef;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;
import com.dataforge.model.ValidationReport;
import com.dataforge.validation.SqlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 修正流水线（P4）：表完成事件触发修正，小表修正不阻塞大表生成。
 *
 * <p>机制：{@link DefaultGenerationAgent#generate} 边生成边触发 {@code TableCompletedListener}，
 * 每张表生成完成即落库 + 该表校验 + 修正（依赖满足时），虚拟线程并行。
 * 修正任务依赖图（表对粒度）：该表 FK 父表 + 该表 correctionRules 引用的 ref_table
 * 全部生成完成才启动修正——跨表规则（AGG_EQUAL 金额）等 ref_table 就绪后重算。</p>
 *
 * <p>并行安全：表完成事件并发触发，修正任务按表去重（fixStarted），独立表并行修正。</p>
 */
@Component
public class CorrectionPipeline {

    private static final Logger log = LoggerFactory.getLogger(CorrectionPipeline.class);

    private final StagingManager staging;
    private final SqlValidator validator;
    private final CorrectionEngine engine;
    private final GeneratorProperties props;
    private final DefaultGenerationAgent generationAgent;

    // run 状态（每次 run 重置）
    private String schemaName;
    private List<TableMeta> tables;
    private ProjectRules rules;
    private Map<String, Set<String>> correctionDeps;
    private final Set<String> completed = ConcurrentHashMap.newKeySet();
    private final Set<String> fixStarted = ConcurrentHashMap.newKeySet();
    private final List<CompletableFuture<Void>> fixFutures = new CopyOnWriteArrayList<>();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger tablesFixed = new AtomicInteger();
    private final AtomicInteger fixRounds = new AtomicInteger();
    private final AtomicInteger fixCommands = new AtomicInteger();

    CorrectionPipeline(StagingManager staging, SqlValidator validator, CorrectionEngine engine,
                       GeneratorProperties props, DefaultGenerationAgent generationAgent) {
        this.staging = staging;
        this.validator = validator;
        this.engine = engine;
        this.props = props;
        this.generationAgent = generationAgent;
    }

    /** 流水线结果：最终修正后数据 + 修正统计 */
    public record PipelineOutcome(
            Map<String, GeneratedData> data,
            int tablesFixed, int fixRounds, int fixCommands
    ) {
    }

    /** 跑一次流水线：生成（边落库）→ 修正（依赖图触发）→ 读回最终数据 */
    public PipelineOutcome run(String schemaName, List<TableMeta> tables, ProjectRules rules,
                               GenerationAgent.GenerationRequest request,
                               boolean cacheEnabled, boolean expansionEnabled) {
        this.schemaName = schemaName;
        this.tables = tables;
        this.rules = rules;
        this.correctionDeps = buildDeps(tables, rules);
        this.completed.clear();
        this.fixStarted.clear();
        this.fixFutures.clear();
        this.tablesFixed.set(0);
        this.fixRounds.set(0);
        this.fixCommands.set(0);

        staging.drop(schemaName);            // 回滚 = 重建工作区
        staging.create(schemaName, tables);
        generationAgent.generate(request, cacheEnabled, expansionEnabled, this::onTableCompleted);
        finish();

        Map<String, GeneratedData> finalData = staging.read(schemaName, tables);
        log.info("流水线完成：{} 张表修正 / {} 轮循环 / {} 条修正指令",
                tablesFixed.get(), fixRounds.get(), fixCommands.get());
        return new PipelineOutcome(finalData, tablesFixed.get(), fixRounds.get(), fixCommands.get());
    }

    // ---- 表完成事件（ContentGen 回调，并发触发）----

    private void onTableCompleted(String tableName, GeneratedData data) {
        try {
            staging.load(schemaName, Map.of(tableName, data));
        } catch (Exception e) {
            log.error("表 `{}` 流水线落库失败（该表修正跳过，最终校验报告兜底）: {}", tableName, e.getMessage());
        }
        completed.add(tableName);
        // 依赖满足的表 → 启动修正（fixStarted 去重，独立表并行）
        for (TableMeta t : tables) {
            if (depsReady(t.name()) && fixStarted.add(t.name())) {
                fixFutures.add(CompletableFuture.runAsync(() -> fixTable(t), pool));
            }
        }
    }

    private boolean depsReady(String table) {
        return correctionDeps.get(table).stream().allMatch(completed::contains);
    }

    /** 单表修正：校验该表 → 修正收敛（作用域仅该表，回归回滚只恢复该表） */
    private void fixTable(TableMeta table) {
        try {
            ValidationReport report = validator.validate(List.of(table), rules, schemaName);
            if (report.totalErrors() == 0) {
                log.info("流水线 `{}`：校验通过，无需修正", table.name());
                return;
            }
            Map<String, GeneratedData> snapshot = staging.read(schemaName, List.of(table));
            CorrectionAgent.CorrectionResult result = engine.run(new CorrectionAgent.CorrectionRequest(
                    List.of(table), snapshot, report, rules, schemaName, null), props.maxIterations());
            tablesFixed.incrementAndGet();
            fixRounds.addAndGet(result.iterations());
            fixCommands.addAndGet(result.appliedFixes().size());
            if (result.rolledBack()) {
                log.warn("流水线 `{}` 回归回滚：该表已恢复修正前快照", table.name());
            }
            log.info("流水线修正 `{}`：{} 轮 / 修正 {} 条 / 收敛={}",
                    table.name(), result.iterations(), result.appliedFixes().size(), result.converged());
        } catch (Exception e) {
            log.warn("流水线修正 `{}` 失败（该表修正跳过，最终校验报告兜底）: {}", table.name(), e.getMessage());
        }
    }

    /**
     * 修正任务依赖图：该表 FK 父表 ∪ correctionRules 引用表（表对粒度）。
     * 只计算<b>生成集内</b>的表——生成集外父表由引用闭包保证已存在（staging 建表），
     * 不作为修正前置（避免依赖永远不满足而阻塞）。
     */
    private Map<String, Set<String>> buildDeps(List<TableMeta> tables, ProjectRules rules) {
        Set<String> inSet = tables.stream().map(TableMeta::name).collect(java.util.stream.Collectors.toSet());
        Map<String, Set<String>> deps = new HashMap<>();
        for (TableMeta t : tables) {
            Set<String> set = new HashSet<>();
            for (ForeignKeyDef fk : t.foreignKeys()) {
                if (inSet.contains(fk.refTable())) set.add(fk.refTable());
            }
            for (ProjectRules.Rule r : rules.correctionRules()) {
                if (r.table().equals(t.name())) {
                    String ref = r.params().get("ref_table");
                    if (ref != null && inSet.contains(ref)) set.add(ref);
                }
            }
            set.remove(t.name());
            deps.put(t.name(), set);
        }
        return deps;
    }

    /** 等待全部修正任务完成并回收线程池 */
    private void finish() {
        try {
            CompletableFuture.allOf(fixFutures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.warn("流水线修正收尾异常: {}", e.getMessage());
        }
        pool.shutdown();
    }
}
