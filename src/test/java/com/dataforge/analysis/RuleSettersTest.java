package com.dataforge.analysis;

import com.dataforge.expansion.PlanBuilder;
import com.dataforge.model.GenerationPlan;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;
import com.dataforge.parser.DdlParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批次3 单测（决策 B=方式 1，修 D3）：LLM 规则参数 → plan 列模式。
 *
 * <p>ENUM_WEIGHT → weighted（权重生效）；VALUE_RANGE → valueRange（范围生效）。</p>
 */
class RuleSettersTest {

    @Test
    void enumsWeightAndValueRangeAppliedToPlan() throws Exception {
        String ddl = "CREATE TABLE orders (\n"
                + "  id BIGINT PRIMARY KEY AUTO_INCREMENT,\n"
                + "  status TINYINT COMMENT '状态：0待支付 1已支付 2已发货 3已收货 4已完成',\n"
                + "  total_amount DECIMAL(10,2)\n"
                + ");";
        List<TableMeta> tables = new DdlParser().parse(ddl).tables();
        GenerationPlan plan = PlanBuilder.build(tables);

        List<ProjectRules.Rule> rules = List.of(
                new ProjectRules.Rule("orders", "status", "ENUM_WEIGHT",
                        "状态绝大多数为 4", 0.8, ProjectRules.Rule.Source.AGENT,
                        Map.of("weights", "4:0.85")),
                new ProjectRules.Rule("orders", "total_amount", "VALUE_RANGE",
                        "总额 10-1000", 0.6, ProjectRules.Rule.Source.AGENT,
                        Map.of("min", "10", "max", "1000")));

        GenerationPlan applied = RuleSetters.apply(plan, rules);
        GenerationPlan.TablePlan tp = applied.tablePlan("orders");
        assertNotNull(tp, "orders 应有计划");
        GenerationPlan.ColumnPlan status = find(tp, "status");
        assertNotNull(status, "status 应有列模式");
        assertEquals("weighted", status.mode(), "ENUM_WEIGHT → weighted 模式");
        assertEquals("4:0.85", status.param("weights"), "权重参数应生效");
        GenerationPlan.ColumnPlan total = find(tp, "total_amount");
        assertNotNull(total, "total_amount 应有列模式");
        assertEquals("valueRange", total.mode(), "VALUE_RANGE → valueRange 模式");
        assertEquals("10", total.param("min"), "min 应生效");
        assertEquals("1000", total.param("max"), "max 应生效");
    }

    @Test
    void unrelatedRulesLeavePlanUntouched() throws Exception {
        String ddl = "CREATE TABLE book (\n"
                + "  id BIGINT PRIMARY KEY AUTO_INCREMENT,\n"
                + "  title VARCHAR(100)\n"
                + ");";
        List<TableMeta> tables = new DdlParser().parse(ddl).tables();
        GenerationPlan plan = PlanBuilder.build(tables);

        // 规则命中不存在的表/列 → plan 不变
        List<ProjectRules.Rule> rules = List.of(
                new ProjectRules.Rule("orders", "status", "ENUM_WEIGHT",
                        "无关", 0.5, ProjectRules.Rule.Source.AGENT, Map.of("weights", "1:1")));
        GenerationPlan applied = RuleSetters.apply(plan, rules);
        assertEquals(plan.tables().size(), applied.tables().size());
        assertNull(applied.tablePlan("orders"), "不应新增不存在的表");
        assertNotNull(applied.tablePlan("book"));
    }

    /** 数据真实性护栏：真实 LLM 提炼的宽范围 VALUE_RANGE 被拒绝；加权枚举列不被覆盖 */
    @Test
    void wideRangeAndWeightedColumnsProtected() throws Exception {
        String ddl = "CREATE TABLE orders (\n"
                + "  id BIGINT PRIMARY KEY AUTO_INCREMENT,\n"
                + "  total_amount DECIMAL(10,2),\n"
                + "  status TINYINT COMMENT '0待支付 1已支付 2已完成'\n"
                + ");";
        List<TableMeta> tables = new DdlParser().parse(ddl).tables();
        GenerationPlan plan = PlanBuilder.build(tables);  // total_amount→const；status→weighted

        // 真实 LLM 提炼的垃圾规则：金额 0~1e8、status 0~6
        List<ProjectRules.Rule> rules = List.of(
                new ProjectRules.Rule("orders", "total_amount", "VALUE_RANGE",
                        "金额范围", 0.5, ProjectRules.Rule.Source.AGENT,
                        Map.of("min", "0", "max", "9.999999999E7")),
                new ProjectRules.Rule("orders", "status", "VALUE_RANGE",
                        "状态范围", 0.5, ProjectRules.Rule.Source.AGENT,
                        Map.of("min", "0", "max", "6")));

        GenerationPlan applied = RuleSetters.apply(plan, rules);
        GenerationPlan.ColumnPlan total = find(applied.tablePlan("orders"), "total_amount");
        assertNotEquals("valueRange", total.mode(), "0~1e8 宽范围应被拒绝（金额由对账兜底）");
        GenerationPlan.ColumnPlan status = find(applied.tablePlan("orders"), "status");
        assertEquals("weighted", status.mode(), "加权枚举列不被 VALUE_RANGE 覆盖");
    }

    /** PlanBuilder weighted dominant：COMMENT "绝大多数为 X" → 4:0.85 非均匀（修 status 权重真实感） */
    @Test
    void weightedDominantFromComment() throws Exception {
        String ddl = "CREATE TABLE orders (\n"
                + "  id BIGINT PRIMARY KEY AUTO_INCREMENT,\n"
                + "  status TINYINT COMMENT '0待支付 1已支付 2已发货 3已收货 4已完成（绝大多数为 4 已完成）'\n"
                + ");";
        List<TableMeta> tables = new DdlParser().parse(ddl).tables();
        GenerationPlan plan = PlanBuilder.build(tables);
        GenerationPlan.ColumnPlan status = find(plan.tablePlan("orders"), "status");
        assertNotNull(status, "status 应有列模式");
        assertEquals("weighted", status.mode());
        String weights = status.param("weights");
        assertTrue(weights != null && weights.contains("4:0.85"), "dominant 应为 4:0.85: " + weights);
        assertFalse(weights.contains("4:1"), "不应是均匀权重");
    }

    /** PlanBuilder dominant 语义终结态兜底：MySQL comment 常缺"绝大多数"描述，靠"4已完成"识别 */
    @Test
    void weightedDominantFromSemanticFinalState() throws Exception {
        String ddl = "CREATE TABLE orders (\n"
                + "  id BIGINT PRIMARY KEY AUTO_INCREMENT,\n"
                + "  status TINYINT COMMENT '状态：0待支付 1已支付 2已发货 3已收货 4已完成 5已取消 6已退款'\n"
                + ");";
        List<TableMeta> tables = new DdlParser().parse(ddl).tables();
        GenerationPlan plan = PlanBuilder.build(tables);
        GenerationPlan.ColumnPlan status = find(plan.tablePlan("orders"), "status");
        assertNotNull(status, "status 应有列模式");
        assertEquals("weighted", status.mode());
        String weights = status.param("weights");
        assertTrue(weights.contains("4:0.85"), "语义终结态 dominant 应为 4:0.85: " + weights);
    }

    private static GenerationPlan.ColumnPlan find(GenerationPlan.TablePlan tp, String column) {
        return tp.columns().stream().filter(c -> c.column().equals(column)).findFirst().orElse(null);
    }
}
