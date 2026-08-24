package com.dataforge.validation;

import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;
import com.dataforge.parser.DdlParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 校验器 V2 单测：新规则类型（VALUE_RANGE / NON_NULL / FORMAT / STATUS_FLOW）编译正确性
 * + 参数结构校验（validateParams 拦截错参数，不静默）。
 */
class MetaRuleCompilerV2Test {

    /** 自包含 orders 表（不依赖 shop DDL，测试稳定） */
    private static TableMeta orders() throws Exception {
        String ddl = "CREATE TABLE orders ("
                + " id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                + " order_no VARCHAR(32) NOT NULL,"
                + " status TINYINT DEFAULT 0,"
                + " total_amount DECIMAL(10,2),"
                + " pay_time DATETIME,"
                + " deliver_time DATETIME,"
                + " receive_time DATETIME"
                + ");";
        return new DdlParser().parse(ddl).tables().get(0);
    }

    private static ProjectRules.Rule rule(String table, String col, String type, Map<String, String> params) {
        return new ProjectRules.Rule(table, col, type, "测试规则", 0.9,
                ProjectRules.Rule.Source.AGENT, params);
    }

    @Test
    void valueRangeCompiles() throws Exception {
        String sql = new MetaRuleCompiler().compileProjectRule(
                rule("orders", "total_amount", "VALUE_RANGE", Map.of("min", "0", "max", "100000")),
                orders(), "shop_staging");
        assertTrue(sql.contains("`total_amount` < 0"), "范围下界应编译");
        assertTrue(sql.contains("`total_amount` > 100000"), "范围上界应编译");
    }

    @Test
    void valueRangeRejectsMinGreaterThanMax() throws Exception {
        assertThrows(IllegalStateException.class, () -> new MetaRuleCompiler().compileProjectRule(
                rule("orders", "total_amount", "VALUE_RANGE", Map.of("min", "100", "max", "0")),
                orders(), "shop_staging"), "min > max 应拒绝");
    }

    @Test
    void valueRangeRejectsMissingParams() throws Exception {
        assertThrows(IllegalStateException.class, () -> new MetaRuleCompiler().compileProjectRule(
                rule("orders", "total_amount", "VALUE_RANGE", Map.of()),
                orders(), "shop_staging"), "缺 min/max 应拒绝");
    }

    @Test
    void nonNullCompiles() throws Exception {
        String sql = new MetaRuleCompiler().compileProjectRule(
                rule("orders", "order_no", "NON_NULL", Map.of()),
                orders(), "shop_staging");
        assertTrue(sql.contains("`order_no` IS NULL"), "NON_NULL 应编译为 IS NULL 检查");
    }

    @Test
    void formatCompiles() throws Exception {
        String sql = new MetaRuleCompiler().compileProjectRule(
                rule("orders", "order_no", "FORMAT", Map.of("regex", "^[A-Z0-9-]+$")),
                orders(), "shop_staging");
        assertTrue(sql.contains("NOT REGEXP"), "FORMAT 应编译为正则检查");
    }

    @Test
    void statusFlowCompiles() throws Exception {
        String sql = new MetaRuleCompiler().compileProjectRule(
                rule("orders", "status", "STATUS_FLOW",
                        Map.of("flow", "pay_time,deliver_time,receive_time", "trigger_status", "4")),
                orders(), "shop_staging");
        assertTrue(sql.contains("`pay_time` >= `deliver_time`"), "时间线递增检查应编译");
        assertTrue(sql.contains("`status` = 4"), "触发状态条件应编译");
    }

    @Test
    void unknownColumnRejected() throws Exception {
        assertThrows(IllegalStateException.class, () -> new MetaRuleCompiler().compileProjectRule(
                rule("orders", "nonexistent_col", "NON_NULL", Map.of()),
                orders(), "shop_staging"), "不存在的列应拒绝编译");
    }

    @Test
    void statusFlowMissingColumnRejected() throws Exception {
        assertThrows(IllegalStateException.class, () -> new MetaRuleCompiler().compileProjectRule(
                rule("orders", "status", "STATUS_FLOW", Map.of("flow", "pay_time,nonexistent_time")),
                orders(), "shop_staging"), "flow 含不存在列应拒绝");
    }
}
