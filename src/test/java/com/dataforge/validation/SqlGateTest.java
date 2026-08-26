package com.dataforge.validation;

import com.dataforge.model.TableMeta;
import com.dataforge.parser.DdlParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 自定义 SQL 结构闸门单测（CUSTOM_SQL 安全阀）：check_sql 只读 SELECT 三道闸
 * （语法/单语句 + 语句类型/危险函数 + schema 作用域/表列存在性/id 锚定），
 * fix_sql 修复 UPDATE（id 锚定防全表）各自拒绝边界。
 */
class SqlGateTest {

    /** 自包含 orders 表（含 status/logistics_no，供 CUSTOM_SQL check/fix 引用） */
    private static TableMeta orders() throws Exception {
        String ddl = "CREATE TABLE orders ("
                + " id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                + " status TINYINT DEFAULT 0,"
                + " logistics_no VARCHAR(32)"
                + ");";
        return new DdlParser().parse(ddl).tables().get(0);
    }

    private static final String CHECK_OK = "SELECT t.`id` AS `id` FROM {schema}.orders t"
            + " WHERE t.`status` >= 2 AND (t.`logistics_no` IS NULL OR t.`logistics_no` = '')";
    private static final String FIX_OK = "UPDATE {schema}.orders SET logistics_no = CONCAT('DF-LOG-', {id})"
            + " WHERE id = {id}";

    // ---- check 模式：通过 ----

    @Test
    void checkSelectAccepted() throws Exception {
        TableMeta orders = orders();
        String sql = SqlGate.compileCheckSql(CHECK_OK, "shop_staging", orders, List.of(orders));
        assertTrue(sql.contains("`shop_staging`"), "应替换 {schema} 为 quote(staging)");
        assertTrue(sql.contains("logistics_no"), "应保留业务条件");
    }

    // ---- check 模式：拒绝边界 ----

    @Test
    void checkSelectRejectsNonSelect() throws Exception {
        TableMeta orders = orders();
        for (String bad : List.of(
                "DELETE FROM {schema}.orders WHERE id = 1",
                "INSERT INTO {schema}.orders (id) VALUES (1)",
                "UPDATE {schema}.orders SET logistics_no = 'x' WHERE id = 1",
                "DROP TABLE {schema}.orders")) {
            assertThrows(IllegalStateException.class,
                    () -> SqlGate.compileCheckSql(bad, "shop_staging", orders, List.of(orders)),
                    "check_sql 必须是只读 SELECT：" + bad);
        }
    }

    @Test
    void checkSelectRejectsMultiStatement() throws Exception {
        assertThrows(IllegalStateException.class, () -> SqlGate.compileCheckSql(
                "SELECT t.`id` AS `id` FROM {schema}.orders t; DROP TABLE x",
                "shop_staging", orders(), List.of(orders())), "禁分号拼接多语句");
    }

    @Test
    void checkSelectRejectsDangerFunction() throws Exception {
        TableMeta orders = orders();
        for (String bad : List.of(
                "SELECT t.`id` AS `id` FROM {schema}.orders t WHERE SLEEP(1)",
                "SELECT t.`id` AS `id` FROM {schema}.orders t WHERE LOAD_FILE('/etc/passwd')",
                "SELECT t.`id` AS `id` FROM {schema}.orders t WHERE BENCHMARK(100, MD5('x'))")) {
            assertThrows(IllegalStateException.class,
                    () -> SqlGate.compileCheckSql(bad, "shop_staging", orders, List.of(orders)),
                    "禁危险函数：" + bad);
        }
    }

    @Test
    void checkSelectRejectsIntoOutfile() throws Exception {
        assertThrows(IllegalStateException.class, () -> SqlGate.compileCheckSql(
                "SELECT t.`id` AS `id` FROM {schema}.orders t INTO OUTFILE '/tmp/x'",
                "shop_staging", orders(), List.of(orders())), "禁 SELECT INTO OUTFILE");
    }

    @Test
    void checkSelectRejectsForUpdate() throws Exception {
        assertThrows(IllegalStateException.class, () -> SqlGate.compileCheckSql(
                "SELECT t.`id` AS `id` FROM {schema}.orders t WHERE t.`status` = 1 FOR UPDATE",
                "shop_staging", orders(), List.of(orders())), "禁 SELECT ... FOR UPDATE");
    }

    @Test
    void checkSelectRejectsBareTable() throws Exception {
        assertThrows(IllegalStateException.class, () -> SqlGate.compileCheckSql(
                "SELECT t.`id` AS `id` FROM orders t WHERE t.`status` >= 2",
                "shop_staging", orders(), List.of(orders())), "禁裸表名（必须 {schema} 限定）");
    }

    @Test
    void checkSelectRejectsUnknownTableOrColumn() throws Exception {
        TableMeta orders = orders();
        assertThrows(IllegalStateException.class, () -> SqlGate.compileCheckSql(
                "SELECT t.`id` AS `id` FROM {schema}.nonexistent t WHERE t.`status` >= 2",
                "shop_staging", orders, List.of(orders)), "表不存在应拒绝");
        assertThrows(IllegalStateException.class, () -> SqlGate.compileCheckSql(
                "SELECT t.`id` AS `id` FROM {schema}.orders t WHERE t.`nonexistent_col` >= 2",
                "shop_staging", orders, List.of(orders)), "列不存在应拒绝");
    }

    @Test
    void checkSelectRejectsMissingId() throws Exception {
        assertThrows(IllegalStateException.class, () -> SqlGate.compileCheckSql(
                "SELECT t.`status` FROM {schema}.orders t WHERE t.`status` >= 2",
                "shop_staging", orders(), List.of(orders())), "结果必须含目标表 id 列");
    }

    @Test
    void checkSelectSkipsExistenceWhenTablesNull() throws Exception {
        // Web 试编译（target/allTables 均 null）：只查语法/SELECT/危险函数，跳过存在性与 id 必需
        String sql = SqlGate.compileCheckSql(CHECK_OK, "schema", null, null);
        assertTrue(sql.contains("`schema`"), "tables 为 null 时仍替换 {schema}");
        assertTrue(SqlGate.checkCheckSql(CHECK_OK, "schema", null, null).isEmpty(),
                "tables 为 null 时合法 SQL 应通过");
    }

    // ---- fix 模式：通过 ----

    @Test
    void fixUpdateAccepted() throws Exception {
        TableMeta orders = orders();
        SqlGate.RenderedFix fix = SqlGate.compileFixSql(FIX_OK, "shop_staging", orders);
        assertTrue(fix.sql().contains("?"), "{id} 应替换为 ? 参数占位符");
        assertTrue(fix.sql().contains("`shop_staging`"), "{schema} 应替换");
        assertEquals(2, fix.paramCount(), "fix_sql 含 2 处 {id}");
    }

    // ---- fix 模式：拒绝边界 ----

    @Test
    void fixUpdateRejectsDeleteDropInsert() throws Exception {
        TableMeta orders = orders();
        for (String bad : List.of(
                "DELETE FROM {schema}.orders WHERE id = {id}",
                "INSERT INTO {schema}.orders (id) VALUES ({id})",
                "DROP TABLE {schema}.orders")) {
            assertThrows(IllegalStateException.class,
                    () -> SqlGate.compileFixSql(bad, "shop_staging", orders),
                    "fix_sql 必须是 UPDATE：" + bad);
        }
    }

    @Test
    void fixUpdateRejectsWrongTable() throws Exception {
        assertThrows(IllegalStateException.class, () -> SqlGate.compileFixSql(
                "UPDATE {schema}.user SET name = 'x' WHERE id = {id}",
                "shop_staging", orders()), "fix_sql 表名必须等于规则目标表");
    }

    @Test
    void fixUpdateRejectsNoIdAnchor() throws Exception {
        assertThrows(IllegalStateException.class, () -> SqlGate.compileFixSql(
                "UPDATE {schema}.orders SET logistics_no = 'x' WHERE status = 2",
                "shop_staging", orders()), "WHERE 必须引用 id（{id} 锚定）");
    }

    @Test
    void fixUpdateRejectsMissingPlaceholder() throws Exception {
        assertThrows(IllegalStateException.class, () -> SqlGate.compileFixSql(
                "UPDATE {schema}.orders SET logistics_no = 'x' WHERE id = 1",
                "shop_staging", orders()), "必须含 {id} 占位符");
    }

    @Test
    void fixUpdateRejectsFullTable() throws Exception {
        assertThrows(IllegalStateException.class, () -> SqlGate.compileFixSql(
                "UPDATE {schema}.orders SET logistics_no = 'x'",
                "shop_staging", orders()), "禁止无 WHERE 的全表 UPDATE");
    }

    @Test
    void fixUpdateRejectsDangerFunction() throws Exception {
        assertThrows(IllegalStateException.class, () -> SqlGate.compileFixSql(
                "UPDATE {schema}.orders SET logistics_no = LOAD_FILE('/etc/passwd') WHERE id = {id}",
                "shop_staging", orders()), "fix_sql 禁危险函数");
    }

    @Test
    void fixUpdateRejectsJoinTarget() throws Exception {
        assertThrows(IllegalStateException.class, () -> SqlGate.compileFixSql(
                "UPDATE {schema}.orders o JOIN {schema}.user u ON o.user_id = u.id"
                        + " SET o.logistics_no = 'x' WHERE o.id = {id}",
                "shop_staging", orders()), "禁止多表/JOIN 作为更新目标");
    }
}
