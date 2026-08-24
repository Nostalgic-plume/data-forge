package com.dataforge.analysis;

import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;
import com.dataforge.parser.DdlParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 垃圾规则门禁单测：宽范围 VALUE_RANGE 与数值枚举列的 VALUE_RANGE 被剔除，合理规则保留。
 */
class RuleFilterTest {

    @Test
    void filtersWideRangeAndEnumColumnRules() throws Exception {
        String ddl = "CREATE TABLE orders (\n"
                + "  id BIGINT PRIMARY KEY AUTO_INCREMENT,\n"
                + "  status TINYINT COMMENT '0待支付 1已支付 4已完成',\n"
                + "  total_amount DECIMAL(10,2),\n"
                + "  discount DECIMAL(10,2) COMMENT '折扣'\n"
                + ");";
        List<TableMeta> tables = new DdlParser().parse(ddl).tables();
        List<ProjectRules.Rule> rules = List.of(
                new ProjectRules.Rule("orders", "total_amount", "VALUE_RANGE", "金额范围",
                        0.5, ProjectRules.Rule.Source.AGENT, Map.of("min", "0", "max", "9.999999999E7")),
                new ProjectRules.Rule("orders", "status", "VALUE_RANGE", "状态范围",
                        0.5, ProjectRules.Rule.Source.AGENT, Map.of("min", "0", "max", "6")),
                new ProjectRules.Rule("orders", "discount", "VALUE_RANGE", "折扣范围",
                        0.5, ProjectRules.Rule.Source.AGENT, Map.of("min", "0", "max", "100")),
                new ProjectRules.Rule("orders", "status", "ENUM_WEIGHT", "状态权重",
                        0.8, ProjectRules.Rule.Source.AGENT, Map.of("weights", "4:0.85")));

        List<ProjectRules.Rule> filtered = RuleFilter.filterGeneration(rules, tables);
        assertEquals(2, filtered.size(), "宽范围 + 枚举列 VALUE_RANGE 应被剔除");
        assertTrue(filtered.stream().anyMatch(r -> r.column().equals("discount")),
                "合理 VALUE_RANGE（0~100）应保留");
        assertTrue(filtered.stream().anyMatch(r -> r.type().equals("ENUM_WEIGHT")),
                "非 VALUE_RANGE 规则应保留");
        assertTrue(filtered.stream().noneMatch(r -> r.column().equals("total_amount")),
                "宽范围金额应剔除");
        assertTrue(filtered.stream().noneMatch(r -> r.column().equals("status") && r.type().equals("VALUE_RANGE")),
                "数值枚举列 VALUE_RANGE 应剔除");
    }

    @Test
    void moneyColumnWithoutMaxFiltered() throws Exception {
        String ddl = "CREATE TABLE orders (id BIGINT PRIMARY KEY AUTO_INCREMENT, total_amount DECIMAL(10,2));";
        List<TableMeta> tables = new DdlParser().parse(ddl).tables();
        List<ProjectRules.Rule> rules = List.of(
                new ProjectRules.Rule("orders", "total_amount", "VALUE_RANGE", "金额≥0",
                        0.5, ProjectRules.Rule.Source.AGENT, Map.of("min", "0")));
        List<ProjectRules.Rule> filtered = RuleFilter.filterGeneration(rules, tables);
        assertTrue(filtered.isEmpty(), "金额列无上界的 VALUE_RANGE 应过滤（金额由对账兜底）");
    }

    @Test
    void emptyOrNullRulesSafe() {
        List<ProjectRules.Rule> empty = RuleFilter.filterGeneration(List.of(), List.of());
        assertTrue(empty.isEmpty());
        assertTrue(RuleFilter.filterGeneration(null, List.of()).isEmpty());
    }
}
