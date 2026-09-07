package com.dataforge.analysis;

import com.dataforge.llm.MockLlmClient;
import com.dataforge.model.GenerationPlan;
import com.dataforge.model.ProjectRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** FORMULA 规则 → expr 列模式接入单测（derive-universal-plan.md T3 验收）。 */
class RuleSettersFormulaTest {

    // ---- 夹具：order_item 派生表计划，subtotal 默认 valueRange ----

    private static GenerationPlan planWithSubtotal(String mode) {
        return new GenerationPlan(List.of(new GenerationPlan.TablePlan(
                "order_item", "derived", null, null,
                List.of(
                        GenerationPlan.ColumnPlan.of("qty", "valueRange", Map.of("min", "1", "max", "9")),
                        GenerationPlan.ColumnPlan.of("price", "const", Map.of("value", "2.5")),
                        GenerationPlan.ColumnPlan.of("subtotal", mode, Map.of("min", "0", "max", "1000"))),
                null)));
    }

    private static ProjectRules.Rule formula(ProjectRules.Rule.Source source) {
        return new ProjectRules.Rule("order_item", "subtotal", "FORMULA",
                "小计 = 单价 × 数量", 0.9, source, Map.of("expr", "round(price * qty, 2)"));
    }

    @Test
    void USER_CONFIRMED的FORMULA落到expr列() {
        GenerationPlan out = RuleSetters.apply(planWithSubtotal("valueRange"), List.of(formula(
                ProjectRules.Rule.Source.USER_CONFIRMED)));
        GenerationPlan.ColumnPlan subtotal = out.tables().get(0).columns().get(2);
        assertEquals("expr", subtotal.mode());
        assertEquals("round(price * qty, 2)", subtotal.param("expr"));
    }

    @Test
    void USER_OVERRIDE同样可落() {
        GenerationPlan out = RuleSetters.apply(planWithSubtotal("valueRange"), List.of(formula(
                ProjectRules.Rule.Source.USER_OVERRIDE)));
        assertEquals("expr", out.tables().get(0).columns().get(2).mode());
    }

    @Test
    void AGENT提议未确认_不落plan保留原模式() {
        GenerationPlan out = RuleSetters.apply(planWithSubtotal("valueRange"), List.of(formula(
                ProjectRules.Rule.Source.AGENT)));
        GenerationPlan.ColumnPlan subtotal = out.tables().get(0).columns().get(2);
        assertEquals("valueRange", subtotal.mode(), "AGENT 表达式未确认不得驱动生成");
        assertNull(subtotal.param("expr"));
    }

    @Test
    void 目标列已被weighted覆盖_不被FORMULA覆盖() {
        GenerationPlan plan = new GenerationPlan(List.of(new GenerationPlan.TablePlan(
                "order_item", "derived", null, null,
                List.of(GenerationPlan.ColumnPlan.of("status", "weighted", Map.of("weights", "4:0.85"))),
                null)));
        ProjectRules.Rule r = new ProjectRules.Rule("order_item", "status", "FORMULA",
                "状态由表达式计算", 0.9, ProjectRules.Rule.Source.USER_CONFIRMED,
                Map.of("expr", "4"));
        GenerationPlan out = RuleSetters.apply(plan, List.of(r));
        assertEquals("weighted", out.tables().get(0).columns().get(0).mode());
    }

    @Test
    void FORMULA缺params_expr_不落() {
        ProjectRules.Rule noExpr = new ProjectRules.Rule("order_item", "subtotal", "FORMULA",
                "小计", 0.9, ProjectRules.Rule.Source.USER_CONFIRMED, Map.of());
        GenerationPlan out = RuleSetters.apply(planWithSubtotal("valueRange"), List.of(noExpr));
        assertEquals("valueRange", out.tables().get(0).columns().get(2).mode());
    }

    @Test
    void refineRules指令可产出USER_CONFIRMED的FORMULA() {
        List<ProjectRules.Rule> rules = new MockLlmClient().refineRules(List.of(), "把小计改成单价乘数量的公式");
        ProjectRules.Rule formula = rules.stream().filter(r -> r.type().equals("FORMULA"))
                .findFirst().orElse(null);
        assertEquals("order_item", formula.table());
        assertEquals("subtotal", formula.column());
        assertEquals(ProjectRules.Rule.Source.USER_CONFIRMED, formula.source());
        assertEquals("round(price * quantity, 2)", formula.params().get("expr"));
    }
}
