package com.dataforge.agent;

import com.dataforge.cache.GenerationCache;
import com.dataforge.config.GeneratorProperties;
import com.dataforge.derivation.ReconcileEngine;
import com.dataforge.expansion.ExpansionEngine;
import com.dataforge.llm.DataLlm;
import com.dataforge.model.ColumnDef;
import com.dataforge.model.DataRow;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.GenerationPlan;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;
import com.dataforge.pipeline.*;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 生成 agent 默认实现：编排 PkGen → FkAlloc → ContentGen（5 优化）→ 跨表对账。
 *
 * <p>缓存/断点续跑：缓存目录取 {@link GeneratorProperties#cacheDir()}（null 则禁用）；
 * CLI 的 --no-cache 走 {@link #generate(GenerationRequest, boolean)} 重载。</p>
 */
@Service
public class DefaultGenerationAgent implements GenerationAgent {

    private final DataLlm llm;
    private final GeneratorProperties props;
    private final ExpansionEngine expansion;

    public DefaultGenerationAgent(DataLlm llm, GeneratorProperties props, ExpansionEngine expansion) {
        this.llm = llm;
        this.props = props;
        this.expansion = expansion;
    }

    /** 设置生成计划（分析 agent 产出；空计划 = 内置模板 fallback，通用化配置驱动） */
    public void setPlan(GenerationPlan plan) {
        if (expansion != null) {
            expansion.setPlan(plan);
        }
    }

    /** correctionRules 中 AGG_EQUAL 规则（生成侧回填消费；无则空） */
    private static List<ProjectRules.Rule> aggRules(ProjectRules rules) {
        if (rules == null || rules.correctionRules() == null) return List.of();
        return rules.correctionRules().stream()
                .filter(r -> "AGG_EQUAL".equals(r.type()))
                .toList();
    }

    /** A1：分析产出的逐表 PK 策略 hint（SourceScanner @TableId 判定）→ PkGen 消费（fallback 全局） */
    private void applyPkHints(PipelineContext context) {
        if (expansion == null) return;
        GenerationPlan plan = expansion.plan();
        if (plan == null || plan.tables().isEmpty()) return;
        for (GenerationPlan.TablePlan tp : plan.tables()) {
            if (tp.pkStrategy() != null) {
                context.setPkStrategy(tp.table(), tp.pkStrategy());
            }
        }
    }

    @Override
    public GenerationResult generate(GenerationRequest req) {
        return generate(req, props.cacheDir() != null);
    }

    public GenerationResult generate(GenerationRequest req, boolean cacheEnabled) {
        return generate(req, cacheEnabled, null);
    }

    /** P4 流水线版：listener 非空时边生成边触发表完成事件（边落库边修正）；生成恒走两阶段（LLM 语义样本 + 确定性展开器） */
    public GenerationResult generate(GenerationRequest req, boolean cacheEnabled,
                                     com.dataforge.pipeline.TableCompletedListener listener) {
        long start = System.currentTimeMillis();

        // 有效参数：请求（CLI 覆盖）优先，其余取配置默认
        GeneratorProperties effective = new GeneratorProperties(
                props.mockLlm(), req.seed(), req.batchSize(), req.maxConcurrency(),
                props.pkStrategy(), props.cacheDir(), props.minParentRows(), props.maxIterations());

        PipelineContext context = new PipelineContext(req.tables(), req.rowCounts(), effective.seed());
        PkGen.Strategy strategy = effective.pkStrategy().equals("snowflake")
                ? PkGen.Strategy.SNOWFLAKE : PkGen.Strategy.SEQUENTIAL;
        applyPkHints(context);   // A1：分析产出的逐表 PK 策略 hint → PkGen（fallback 全局）
        PkGen.execute(context, strategy);
        FkAlloc.execute(context);

        GenerationCache cache = cacheEnabled && effective.cacheDir() != null
                ? new GenerationCache(Path.of(effective.cacheDir())) : null;
        ContentGen.GenOutcome outcome = ContentGen.execute(context, llm, effective, cache,
                expansion, listener, null, req.rules());

        Map<String, GeneratedData> data = outcome.results().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().data()));
        // 通用聚合回填（AGG_EQUAL 规则驱动，一份规则两处复用）→ legacy Reconciliation 兜底行内/actual
        boolean aggApplied = ReconcileEngine.apply(data, aggRules(req.rules()));
        Reconciliation.reconcile(data, aggApplied);

        Map<String, Integer> actualCounts = new LinkedHashMap<>();
        Map<String, GenerationAgent.TableStat> tableStats = new LinkedHashMap<>();
        outcome.results().forEach((table, r) -> {
            actualCounts.put(table, r.data().rowCount());
            tableStats.put(table, new GenerationAgent.TableStat(
                    context.rowCount(table), r.data().rowCount(), r.elapsedMs(),
                    r.llmBatches(), r.downshifts(), r.gapFills(), r.cacheHit()));
        });
        return new GenerationResult(data, outcome.failedTables(),
                System.currentTimeMillis() - start, actualCounts, tableStats);
    }

    /**
     * P5 增量生成：全量是增量的特例。
     * baseline 为现有数据（staging/目标库读入），增量语义：
     * <ul>
     *   <li><b>起始 ID=MAX+1</b>：PkGen SEQUENTIAL 从现有最大 id 之后开始（追加不冲突）</li>
     *   <li><b>现有池引用</b>：FkAlloc 父表池 = 现有 PK ∪ 新增 PK（FK 跨现有）</li>
     *   <li><b>跨现有引用</b>：ContentGen 预填现有父表列值索引（展开器/LLM 可见现有数据）</li>
     *   <li><b>跨现有对账</b>：Reconciliation 对 baseline ∪ 新增 跑（现有金额正确→重算不变，不误伤）</li>
     * </ul>
     */
    public GenerationResult generateIncremental(GenerationRequest req,
                                                Map<String, GeneratedData> baseline,
                                                boolean cacheEnabled) {
        long start = System.currentTimeMillis();
        GeneratorProperties effective = new GeneratorProperties(
                props.mockLlm(), req.seed(), req.batchSize(), req.maxConcurrency(),
                props.pkStrategy(), props.cacheDir(), props.minParentRows(), props.maxIterations());

        // 增量只生成用户指定的表（scope）；闭包父表 rowCount=0 → 不生成，引用现有（existingPools）
        Map<String, Integer> newRows = new LinkedHashMap<>();
        for (TableMeta t : req.tables()) {
            newRows.put(t.name(), req.rowCounts().getOrDefault(t.name(), 0));
        }
        PipelineContext context = new PipelineContext(req.tables(), newRows, effective.seed());
        // 增量上下文：起始 ID = 现有 MAX(id)+1；现有 PK 池（FK 跨现有引用）
        for (GeneratedData data : baseline.values()) {
            long maxId = data.rows().stream()
                    .map(r -> r.values().get("id"))
                    .filter(Objects::nonNull)
                    .mapToLong(v -> ((Number) v).longValue())
                    .max().orElse(0L);
            context.setStartId(data.tableName(), maxId + 1);
            for (ColumnDef col : data.columns()) {
                if (col.primaryKey()) {
                    List<Object> pkValues = data.rows().stream()
                            .map(r -> r.values().get(col.name()))
                            .filter(Objects::nonNull).toList();
                    context.setExistingPool(data.tableName(), pkValues);
                }
            }
        }
        PkGen.Strategy strategy = effective.pkStrategy().equals("snowflake")
                ? PkGen.Strategy.SNOWFLAKE : PkGen.Strategy.SEQUENTIAL;
        applyPkHints(context);   // A1：分析产出的逐表 PK 策略 hint → PkGen（fallback 全局）
        PkGen.execute(context, strategy);
        FkAlloc.execute(context);

        GenerationCache cache = cacheEnabled && effective.cacheDir() != null
                ? new GenerationCache(Path.of(effective.cacheDir())) : null;
        Map<String, Map<String, List<Object>>> initialCompleted =
                ContentGen.buildCompletedIndex(baseline);
        ContentGen.GenOutcome outcome = ContentGen.execute(context, llm, effective, cache,
                expansion, null, initialCompleted, req.rules());

        // 结果 = 现有 baseline 拼接新增（同表现有行 + 新增行，现有行保留不被覆盖）
        Map<String, GeneratedData> data = new LinkedHashMap<>();
        for (TableMeta t : req.tables()) {
            GeneratedData existing = baseline.get(t.name());
            GeneratedData added = outcome.results().get(t.name()) == null
                    ? null : outcome.results().get(t.name()).data();
            if (existing == null && added == null) continue;
            List<DataRow> allRows = new java.util.ArrayList<>();
            if (existing != null) allRows.addAll(existing.rows());
            if (added != null) allRows.addAll(added.rows());
            data.put(t.name(), new GeneratedData(t.name(), t.columns(), allRows));
        }
        // 跨现有对账（AGG 规则驱动优先；现有金额正确行重算不变，不误伤）
        boolean aggApplied = ReconcileEngine.apply(data, aggRules(req.rules()));
        Reconciliation.reconcile(data, aggApplied);

        Map<String, Integer> actualCounts = new LinkedHashMap<>();
        Map<String, GenerationAgent.TableStat> tableStats = new LinkedHashMap<>();
        outcome.results().forEach((table, r) -> {
            actualCounts.put(table, r.data().rowCount());
            tableStats.put(table, new GenerationAgent.TableStat(
                    context.rowCount(table), r.data().rowCount(), r.elapsedMs(),
                    r.llmBatches(), r.downshifts(), r.gapFills(), r.cacheHit()));
        });
        return new GenerationResult(data, outcome.failedTables(),
                System.currentTimeMillis() - start, actualCounts, tableStats);
    }
}
