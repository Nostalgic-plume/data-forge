package com.dataforge.correction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 修正 SQL V2 模板单测：新规则类型（VALUE_RANGE / NON_NULL / FORMAT / STATUS_FLOW）的
 * check/apply SQL 正确性（含 scopeMinId 增量限定）。
 */
class CorrectionSqlsV2Test {

    @Test
    void valueRangeCheckHasScopeAndBounds() {
        String sql = CorrectionSqls.valueRangeCheckSql("`shop_staging`", "orders", "total_amount",
                "0", "100000", 100L);
        assertTrue(sql.contains("`total_amount` < 0"), "下界条件应编译");
        assertTrue(sql.contains("`total_amount` > 100000"), "上界条件应编译");
        assertTrue(sql.contains("`id` > 100"), "增量作用域应限定（scopeMinId）");
    }

    @Test
    void valueRangeApplyClamps() {
        String sql = CorrectionSqls.valueRangeApplySql("`shop_staging`", "orders", "total_amount",
                "0", "100000", null);
        assertTrue(sql.startsWith("UPDATE"), "apply 应为 UPDATE");
        assertTrue(sql.contains("LEAST(GREATEST"), "双侧范围应 LEAST(GREATEST) 钳制");
        assertFalse(sql.contains("`id` >"), "全量模式无作用域限定");
    }

    @Test
    void nonNullCheckSql() {
        String sql = CorrectionSqls.nonNullCheckSql("`shop_staging`", "orders", "order_no", null);
        assertTrue(sql.contains("`order_no` IS NULL"), "NON_NULL check 应为 IS NULL");
    }

    @Test
    void formatCheckSql() {
        String sql = CorrectionSqls.formatCheckSql("`shop_staging`", "orders", "order_no",
                "^[A-Z0-9-]+$", null);
        assertTrue(sql.contains("NOT REGEXP"), "FORMAT check 应为正则检查");
    }

    @Test
    void statusFlowCheckSql() {
        String sql = CorrectionSqls.statusFlowCheckSql("`shop_staging`", "orders",
                "pay_time,deliver_time,receive_time", "4", null, null);
        assertTrue(sql.contains("t.`pay_time` >= t.`deliver_time`"), "时间线递增违规应编译");
        assertTrue(sql.contains("t.`deliver_time` >= t.`receive_time`"), "第二段递增违规应编译");
        assertTrue(sql.contains("`status` = 4"), "触发状态条件应编译");
    }

    // ---- EXPR_EQUAL（行内表达式：check 找差异行，apply 重算覆盖；与 AGG_EQUAL 对称） ----

    private static final String EXPR = "`total_amount` - `discount_amount` + `freight`";

    @Test
    void exprEqualCheckFindsMismatch() {
        String sql = CorrectionSqls.exprEqualCheckSql("`shop_staging`", "orders", "actual_amount", EXPR, 100L);
        assertTrue(sql.startsWith("SELECT"), "check 应为 SELECT");
        assertTrue(sql.contains("AS `current`") && sql.contains("AS `expected`"),
                "应同时给出当前值与期望值（供 id 锚定修正）");
        assertTrue(sql.contains("ABS(t.`actual_amount` - (" + EXPR + "))"),
                "应比对实际值与表达式求值");
        assertTrue(sql.contains("`id` > 100"), "增量作用域应限定");
    }

    @Test
    void exprEqualApplyRewritesTargetColumn() {
        String sql = CorrectionSqls.exprEqualApplySql("`shop_staging`", "orders", "actual_amount", EXPR, null);
        assertTrue(sql.startsWith("UPDATE"), "apply 应为 UPDATE");
        assertTrue(sql.contains("SET t.`actual_amount` = (" + EXPR + ")"),
                "应把目标列改写为表达式求值结果: " + sql);
        assertFalse(sql.contains("`id` >"), "全量模式无作用域限定");
    }
}
