package com.dataforge.analysis;

import com.dataforge.model.GenerationPlan;
import com.dataforge.model.ProjectRules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RuleSetters（决策 B=方式 1，修 D3）：LLM 提炼的规则参数 → 生成计划列模式。
 *
 * <p>不做 agentic 工具调用：extractRules 结构化 params（weights/flow/min-max）产出后，
 * 由本类规范化映射到 {@link GenerationPlan.ColumnPlan}，生成器消费。</p>
 *
 * <p>映射：
 * <ul>
 *   <li>ENUM_WEIGHT（params.weights）→ 列模式 <b>weighted</b></li>
 *   <li>VALUE_RANGE（params.min/max）→ 列模式 <b>valueRange</b></li>
 *   <li>AGG_EQUAL / STATUS_FLOW → 不进 plan（校验/修正规则 / 时间线联动批次 4）</li>
 * </ul></p>
 */
public final class RuleSetters {

    private RuleSetters() {}

    /**
     * 把生成类规则参数应用到 plan 列模式（命中的列替换 mode/params，其余保留）。
     *
     * @param plan  分析产出的生成计划
     * @param rules 前置约束规则（generationRules；ENUM_WEIGHT/VALUE_RANGE 生效）
     * @return 应用后的计划（不可变 record，重建列表）
     */
    public static GenerationPlan apply(GenerationPlan plan, List<ProjectRules.Rule> rules) {
        if (plan == null || rules == null || rules.isEmpty()) return plan;
        List<GenerationPlan.TablePlan> newTables = new ArrayList<>();
        for (GenerationPlan.TablePlan tp : plan.tables()) {
            List<ProjectRules.Rule> tableRules = rules.stream()
                    .filter(r -> r.table() != null && r.table().equals(tp.table()))
                    .toList();
            if (tableRules.isEmpty()) {
                newTables.add(tp);
                continue;
            }
            List<GenerationPlan.ColumnPlan> newCols = new ArrayList<>(tp.columns().size());
            for (GenerationPlan.ColumnPlan cp : tp.columns()) {
                ProjectRules.Rule hit = tableRules.stream()
                        .filter(r -> r.column() != null && r.column().equals(cp.column()))
                        .findFirst().orElse(null);
                GenerationPlan.ColumnPlan replaced = hit == null ? null : toColumnPlan(cp, hit);
                newCols.add(replaced != null ? replaced : cp);
            }
            newTables.add(new GenerationPlan.TablePlan(
                    tp.table(), tp.builder(), tp.parent(), tp.rowsPerParent(), newCols, tp.pkStrategy()));
        }
        return new GenerationPlan(newTables);
    }

    /** 单条规则 → 列模式（命中即替换；不适用返回 null 保留原模式） */
    private static GenerationPlan.ColumnPlan toColumnPlan(GenerationPlan.ColumnPlan cp, ProjectRules.Rule r) {
        return switch (r.type()) {
            case "ENUM_WEIGHT" -> {
                String weights = r.params().get("weights");
                yield weights == null || weights.isBlank() ? null
                        : GenerationPlan.ColumnPlan.of(cp.column(), "weighted", Map.of("weights", weights));
            }
            case "VALUE_RANGE" -> {
                String min = r.params().get("min");
                String max = r.params().get("max");
                if ((min == null || min.isBlank()) && (max == null || max.isBlank())) yield null;
                // 护栏 1：启发式已识别加权枚举列（status/type/deleted → weighted）不被 VALUE_RANGE 覆盖，
                //   避免真实 LLM 提炼"status 0~6"这类宽范围破坏合理枚举权重
                if ("weighted".equals(cp.mode())) yield null;
                // 护栏 2：范围过宽（LLM 占位大值，如金额 total_amount 0~1e8）→ 拒绝，
                //   保留启发式 const（金额由 Reconciliation 对账兜底）
                Double minV = parseDouble(min);
                Double maxV = parseDouble(max);
                if (minV != null && maxV != null && (maxV - minV) > 1_000_000) yield null;
                Map<String, String> p = new LinkedHashMap<>();
                if (min != null && !min.isBlank()) p.put("min", min);
                if (max != null && !max.isBlank()) p.put("max", max);
                yield GenerationPlan.ColumnPlan.of(cp.column(), "valueRange", p);
            }
            default -> null;
        };
    }

    /** 纯数字解析（VALUE_RANGE 护栏用；表达式/空返回 null） */
    private static Double parseDouble(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
