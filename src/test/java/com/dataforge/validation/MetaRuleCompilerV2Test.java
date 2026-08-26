package com.dataforge.validation;

import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;
import com.dataforge.parser.DdlParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 校验器 V2 单测：新规则类型（VALUE_RANGE / NON_NULL / FORMAT / STATUS_FLOW / CUSTOM_SQL）
 * 编译正确性 + 参数结构校验（validateParams 拦截错参数，不静默）。
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

    /** orders + logistics_no（CUSTOM_SQL check/fix 引用） */
    private static TableMeta ordersWithLogistics() throws Exception {
        String ddl = "CREATE TABLE orders ("
                + " id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                + " status TINYINT DEFAULT 0,"
                + " logistics_no VARCHAR(32)"
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
                List.of(orders()), "shop_staging");
        assertTrue(sql.contains("`total_amount` < 0"), "范围下界应编译");
        assertTrue(sql.contains("`total_amount` > 100000"), "范围上界应编译");
    }

    @Test
    void valueRangeRejectsMinGreaterThanMax() throws Exception {
        assertThrows(IllegalStateException.class, () -> new MetaRuleCompiler().compileProjectRule(
                rule("orders", "total_amount", "VALUE_RANGE", Map.of("min", "100", "max", "0")),
                List.of(orders()), "shop_staging"), "min > max 应拒绝");
    }

    @Test
    void valueRangeRejectsMissingParams() throws Exception {
        assertThrows(IllegalStateException.class, () -> new MetaRuleCompiler().compileProjectRule(
                rule("orders", "total_amount", "VALUE_RANGE", Map.of()),
                List.of(orders()), "shop_staging"), "缺 min/max 应拒绝");
    }

    @Test
    void nonNullCompiles() throws Exception {
        String sql = new MetaRuleCompiler().compileProjectRule(
                rule("orders", "order_no", "NON_NULL", Map.of()),
                List.of(orders()), "shop_staging");
        assertTrue(sql.contains("`order_no` IS NULL"), "NON_NULL 应编译为 IS NULL 检查");
    }

    @Test
    void formatCompiles() throws Exception {
        String sql = new MetaRuleCompiler().compileProjectRule(
                rule("orders", "order_no", "FORMAT", Map.of("regex", "^[A-Z0-9-]+$")),
                List.of(orders()), "shop_staging");
        assertTrue(sql.contains("NOT REGEXP"), "FORMAT 应编译为正则检查");
    }

    @Test
    void statusFlowCompiles() throws Exception {
        String sql = new MetaRuleCompiler().compileProjectRule(
                rule("orders", "status", "STATUS_FLOW",
                        Map.of("flow", "pay_time,deliver_time,receive_time", "trigger_status", "4")),
                List.of(orders()), "shop_staging");
        assertTrue(sql.contains("`pay_time` >= `deliver_time`"), "时间线递增检查应编译");
        assertTrue(sql.contains("`status` = 4"), "触发状态条件应编译");
    }

    @Test
    void unknownColumnRejected() throws Exception {
        assertThrows(IllegalStateException.class, () -> new MetaRuleCompiler().compileProjectRule(
                rule("orders", "nonexistent_col", "NON_NULL", Map.of()),
                List.of(orders()), "shop_staging"), "不存在的列应拒绝编译");
    }

    @Test
    void statusFlowMissingColumnRejected() throws Exception {
        assertThrows(IllegalStateException.class, () -> new MetaRuleCompiler().compileProjectRule(
                rule("orders", "status", "STATUS_FLOW", Map.of("flow", "pay_time,nonexistent_time")),
                List.of(orders()), "shop_staging"), "flow 含不存在列应拒绝");
    }

    // ---- CUSTOM_SQL ----

    @Test
    void customSqlCompiles() throws Exception {
        TableMeta orders = ordersWithLogistics();
        String sql = new MetaRuleCompiler().compileProjectRule(
                rule("orders", "logistics_no", "CUSTOM_SQL", Map.of(
                        "check_sql", "SELECT t.`id` AS `id` FROM {schema}.orders t"
                                + " WHERE t.`status` >= 2 AND (t.`logistics_no` IS NULL OR t.`logistics_no` = '')",
                        "fix_sql", "UPDATE {schema}.orders SET logistics_no = CONCAT('DF-LOG-', {id})"
                                + " WHERE id = {id}")),
                List.of(orders), "shop_staging");
        assertTrue(sql.contains("`shop_staging`"), "check_sql 应替换 {schema} 为 quote(staging)");
        assertTrue(sql.contains("logistics_no"), "check_sql 应保留业务条件");
    }

    @Test
    void customSqlRejectsNonSelect() throws Exception {
        assertThrows(IllegalStateException.class, () -> new MetaRuleCompiler().compileProjectRule(
                rule("orders", "logistics_no", "CUSTOM_SQL",
                        Map.of("check_sql", "DELETE FROM {schema}.orders WHERE id = 1")),
                List.of(ordersWithLogistics()), "shop_staging"), "check_sql 必须是只读 SELECT");
    }

    @Test
    void customSqlRejectsMissingSql() throws Exception {
        assertThrows(IllegalStateException.class, () -> new MetaRuleCompiler().compileProjectRule(
                rule("orders", "logistics_no", "CUSTOM_SQL", Map.of()),
                List.of(ordersWithLogistics()), "shop_staging"), "缺 check_sql 应拒绝");
    }

    @Test
    void customSqlRejectsBadFixSql() throws Exception {
        // check_sql 合法但 fix_sql 为 DELETE → 编译期闸门拒绝（Web 试编译可见）
        assertThrows(IllegalStateException.class, () -> new MetaRuleCompiler().compileProjectRule(
                rule("orders", "logistics_no", "CUSTOM_SQL", Map.of(
                        "check_sql", "SELECT t.`id` AS `id` FROM {schema}.orders t"
                                + " WHERE t.`status` >= 2 AND (t.`logistics_no` IS NULL OR t.`logistics_no` = '')",
                        "fix_sql", "DELETE FROM {schema}.orders WHERE id = {id}")),
                List.of(ordersWithLogistics()), "shop_staging"), "坏 fix_sql 应拒绝编译");
    }

    @Test
    void customSqlRejectsUnknownFixColumn() throws Exception {
        assertThrows(IllegalStateException.class, () -> new MetaRuleCompiler().compileProjectRule(
                rule("orders", "logistics_no", "CUSTOM_SQL", Map.of(
                        "check_sql", "SELECT t.`id` AS `id` FROM {schema}.orders t"
                                + " WHERE t.`status` >= 2 AND (t.`logistics_no` IS NULL OR t.`logistics_no` = '')",
                        "fix_column", "nonexistent_col")),
                List.of(ordersWithLogistics()), "shop_staging"), "fix_column 不存在应拒绝");
    }
}
