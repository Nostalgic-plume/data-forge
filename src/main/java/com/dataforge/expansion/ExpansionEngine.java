package com.dataforge.expansion;

import com.dataforge.model.DataRow;
import com.dataforge.model.GenerationPlan;
import com.dataforge.model.TableMeta;
import com.dataforge.model.PipelineContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 展开器引擎（P1 两阶段生成 + 通用化改造）：
 * <b>配置驱动优先（分析 agent 产出的 GenerationPlan）</b>，无计划时 fallback 内置模板。
 *
 * <p>表分类（有 plan 时按 plan.builder）：
 * <ul>
 *   <li><b>source</b>：语义源表（LLM 样本）——不进 isExpanded，走 LLM 分支</li>
 *   <li><b>derived</b>：派生表（零 LLM）——GenericExpander 按计划生成（通用化）</li>
 *   <li><b>fixed</b>：固定枚举表——内置种子或计划种子</li>
 * </ul>
 * 无 plan 时保留 P1 内置分类（Java 展开器模板 + FixedSamples）。</p>
 *
 * <p><b>无状态</b>：本类是 Spring 单例，<b>不持有生成计划</b>——计划随 {@link PipelineContext}
 * 每次运行传入（并发运行不互相覆盖）；判定方法一律显式收 plan 参数。</p>
 */
@Service
public class ExpansionEngine {

    private final Map<String, Expander> expanders;
    private final GenericExpander generic;

    public ExpansionEngine(List<Expander> expanders, GenericExpander generic) {
        this.expanders = expanders.stream()
                .collect(Collectors.toMap(Expander::tableName, e -> e));
        this.generic = generic;
    }

    /** 是否为派生表（配置驱动或内置模板） */
    public boolean isExpanded(GenerationPlan plan, String tableName) {
        GenerationPlan.TablePlan tp = nonNull(plan).tablePlan(tableName);
        if (tp != null) return tp.isDerived();
        return expanders.containsKey(tableName);
    }

    /** 是否为固定样本表 */
    public boolean isFixed(GenerationPlan plan, String tableName) {
        GenerationPlan.TablePlan tp = nonNull(plan).tablePlan(tableName);
        if (tp != null) return "fixed".equals(tp.builder());
        return FixedSamples.isFixed(tableName);
    }

    /** 展开派生表（配置驱动优先，内置模板 fallback）；计划取自本次运行的上下文 */
    public List<DataRow> expand(TableMeta table, PipelineContext context,
                                Map<String, Map<String, List<Object>>> completedData) {
        GenerationPlan.TablePlan tp = nonNull(context.plan()).tablePlan(table.name());
        if (tp != null && tp.isDerived()) {
            return generic.generate(tp, table, context, completedData);
        }
        Expander expander = expanders.get(table.name());
        if (expander == null) return null;
        return expander.expand(table, context, completedData);
    }

    /** 计划中该表的 LLM 语义列（商品名/规格等）：派生表生成后由 ContentGen 批量调 LLM 回填 */
    public List<com.dataforge.model.ColumnDef> llmColumns(GenerationPlan plan, TableMeta table) {
        GenerationPlan.TablePlan tp = nonNull(plan).tablePlan(table.name());
        if (tp == null) return List.of();
        Set<String> llmCols = tp.columns().stream()
                .filter(c -> c.mode().equals("llm"))
                .map(GenerationPlan.ColumnPlan::column)
                .collect(Collectors.toSet());
        return table.columns().stream().filter(c -> llmCols.contains(c.name())).toList();
    }

    private static GenerationPlan nonNull(GenerationPlan plan) {
        return plan == null ? GenerationPlan.empty() : plan;
    }
}
