package com.dataforge.correction;

import com.dataforge.agent.CorrectionAgent;
import com.dataforge.db.JdbcDataSource;
import com.dataforge.db.StagingManager;
import com.dataforge.llm.MockLlmClient;
import com.dataforge.model.*;
import com.dataforge.parser.DdlParser;
import com.dataforge.validation.MetaRuleCompiler;
import com.dataforge.validation.SqlValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 修正引擎 V2 单测：新规则类型桶走规则通道 + 收敛闭环——
 * VALUE_RANGE（范围钳制批量 SQL）+ CUSTOM_SQL（LLM 产 fix_sql 参数化 id 锚定 / 缺失上报残留）。
 */
class CorrectionEngineV2Test {

    /** 可编程 JDBC：记录 execute 的 sql + params；check 首次返回问题行、之后空（修正生效） */
    private static class FakeJdbc implements JdbcDataSource {
        final List<String> executed = new ArrayList<>();
        final List<Object[]> executedParams = new ArrayList<>();
        private boolean valueRangeChecked = false;
        private boolean customChecked = false;

        @Override
        public List<Map<String, Object>> query(String sql, Object... params) {
            if (sql.contains("logistics_no") && !customChecked) {
                customChecked = true;
                return List.of(
                        Map.of("id", 1L, "status", 2, "logistics_no", ""),
                        Map.of("id", 2L, "status", 3, "logistics_no", ""));
            }
            if (sql.contains("`total_amount`") && sql.contains("< 0") && !valueRangeChecked) {
                valueRangeChecked = true;
                return List.of(Map.of("id", 1L, "value", 150000.0));   // 超上界的问题行
            }
            return List.of();
        }

        @Override
        public int execute(String sql, Object... params) {
            executed.add(sql);
            executedParams.add(params == null ? new Object[0] : params.clone());
            return 1;
        }

        @Override public long maxId(String schema, String table) { return 0; }
        @Override public void transaction(Supplier<Void> work) { work.get(); }
    }

    private static TableMeta orders() throws Exception {
        String ddl = "CREATE TABLE orders ("
                + " id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                + " total_amount DECIMAL(10,2) NOT NULL"
                + ");";
        return new DdlParser().parse(ddl).tables().get(0);
    }

    /** orders + status/logistics_no（CUSTOM_SQL 修正目标） */
    private static TableMeta ordersWithLogistics() throws Exception {
        String ddl = "CREATE TABLE orders ("
                + " id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                + " status TINYINT DEFAULT 0,"
                + " logistics_no VARCHAR(32)"
                + ");";
        return new DdlParser().parse(ddl).tables().get(0);
    }

    private static StagingManager fakeStaging() {
        return new StagingManager() {
            @Override public String stagingSchema(String schemaName) { return schemaName + "_staging"; }
            @Override public void create(String schemaName, List<TableMeta> tables) { }
            @Override public void load(String schemaName, Map<String, com.dataforge.model.GeneratedData> data) { }
            @Override public Map<String, com.dataforge.model.GeneratedData> read(String schemaName, List<TableMeta> tables) {
                return Map.of();
            }
            @Override public void drop(String schemaName) { }
        };
    }

    private static ProjectRules.Rule customSqlRule(boolean withFix) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("check_sql", "SELECT t.`id` AS `id` FROM {schema}.orders t"
                + " WHERE t.`status` >= 2 AND (t.`logistics_no` IS NULL OR t.`logistics_no` = '')");
        if (withFix) {
            params.put("fix_sql", "UPDATE {schema}.orders SET logistics_no = CONCAT('DF-LOG-', {id})"
                    + " WHERE id = {id}");
            params.put("fix_column", "logistics_no");
        }
        return new ProjectRules.Rule("orders", "logistics_no", "CUSTOM_SQL",
                "已发货订单必须有物流单号", 0.9, ProjectRules.Rule.Source.AGENT, params);
    }

    private static ValidationReport customReport(int count) {
        ValidationReport.Bucket bucket = new ValidationReport.Bucket(
                "orders", "logistics_no", "CUSTOM_SQL", count, 1.0,
                List.of(Map.of("id", 1L, "status", 2, "logistics_no", "")),
                "已发货订单必须有物流单号", "CUSTOM_SQL");
        return new ValidationReport(List.of(bucket), count, 0L);
    }

    /** VALUE_RANGE 桶 → 规则通道批量修正（范围钳制）→ 再校验收敛 */
    @Test
    void valueRangeBucketConvergesViaRuleChannel() throws Exception {
        TableMeta orders = orders();
        List<TableMeta> tables = List.of(orders);
        FakeJdbc jdbc = new FakeJdbc();
        SqlValidator validator = new SqlValidator(jdbc, new MetaRuleCompiler(), fakeStaging());
        CorrectionEngine engine = new CorrectionEngine(jdbc, fakeStaging(), validator, new MockLlmClient());

        // rules：VALUE_RANGE 金额范围
        ProjectRules rules = new ProjectRules(List.of(),
                List.of(new ProjectRules.Rule("orders", "total_amount", "VALUE_RANGE",
                        "金额 0~100000", 0.9, ProjectRules.Rule.Source.AGENT,
                        Map.of("min", "0", "max", "100000"))),
                List.of(), 1);

        // initialReport：1 个 VALUE_RANGE 桶
        ValidationReport.Bucket bucket = new ValidationReport.Bucket(
                "orders", "total_amount", "VALUE_RANGE", 1, 1.0,
                List.of(Map.of("id", 1L, "total_amount", 150000.0)),
                "金额超范围", "RANGE_VIOLATION");
        ValidationReport initial = new ValidationReport(List.of(bucket), 1, 0L);

        CorrectionAgent.CorrectionResult result = engine.run(
                new CorrectionAgent.CorrectionRequest(tables, Map.of(), initial, rules, "shop", null),
                5);

        assertTrue(result.converged(), "VALUE_RANGE 修正后应收敛，最终错误 " + result.finalReport().totalErrors());
        assertEquals(1, result.iterations(), "一轮应收敛");
        assertTrue(jdbc.executed.stream().anyMatch(s -> s.startsWith("UPDATE")),
                "规则通道应执行批量 UPDATE（范围钳制）");
    }

    /** CUSTOM_SQL 桶 → fix_sql 参数化逐行 id 锚定修正 → 再校验收敛 */
    @Test
    void customSqlBucketConvergesViaFixSql() throws Exception {
        TableMeta orders = ordersWithLogistics();
        List<TableMeta> tables = List.of(orders);
        FakeJdbc jdbc = new FakeJdbc();
        SqlValidator validator = new SqlValidator(jdbc, new MetaRuleCompiler(), fakeStaging());
        CorrectionEngine engine = new CorrectionEngine(jdbc, fakeStaging(), validator, new MockLlmClient());

        ProjectRules rules = new ProjectRules(List.of(), List.of(customSqlRule(true)), List.of(), 1);
        CorrectionAgent.CorrectionResult result = engine.run(
                new CorrectionAgent.CorrectionRequest(tables, Map.of(), customReport(2), rules, "shop", null),
                5);

        assertTrue(result.converged(), "CUSTOM_SQL fix_sql 修正后应收敛，最终错误 " + result.finalReport().totalErrors());
        assertTrue(jdbc.executed.stream().anyMatch(s -> s.contains("SET logistics_no")),
                "应执行参数化 fix_sql（SET logistics_no）");
        // {id} 出现 2 次 → paramCount=2 → 每行 params=[id, id]
        assertTrue(jdbc.executedParams.stream()
                        .anyMatch(p -> p.length == 2 && p[0].equals(1L) && p[1].equals(1L)),
                "fix_sql 参数应绑定问题行 id（两处 {id}）");
    }

    /** CUSTOM_SQL 无 fix_sql → 只检查不修正，上报残留不收敛 */
    @Test
    void customSqlMissingFixSqlReportsResidual() throws Exception {
        TableMeta orders = ordersWithLogistics();
        List<TableMeta> tables = List.of(orders);
        FakeJdbc jdbc = new FakeJdbc();
        SqlValidator validator = new SqlValidator(jdbc, new MetaRuleCompiler(), fakeStaging());
        CorrectionEngine engine = new CorrectionEngine(jdbc, fakeStaging(), validator, new MockLlmClient());

        ProjectRules rules = new ProjectRules(List.of(), List.of(customSqlRule(false)), List.of(), 1);
        CorrectionAgent.CorrectionResult result = engine.run(
                new CorrectionAgent.CorrectionRequest(tables, Map.of(), customReport(2), rules, "shop", null),
                5);

        assertFalse(result.converged(), "无 fix_sql 应上报残留不收敛");
        assertTrue(result.finalReport().totalErrors() > 0, "残余错误应保留");
        assertTrue(jdbc.executed.stream().noneMatch(s -> s.contains("SET logistics_no")),
                "无 fix_sql 不应执行任何修正");
    }

    /** CUSTOM_SQL 坏 fix_sql（DELETE）→ 闸门拦截上报残留，不执行写操作 */
    @Test
    void customSqlBadFixSqlReportsResidual() throws Exception {
        TableMeta orders = ordersWithLogistics();
        List<TableMeta> tables = List.of(orders);
        FakeJdbc jdbc = new FakeJdbc();
        SqlValidator validator = new SqlValidator(jdbc, new MetaRuleCompiler(), fakeStaging());
        CorrectionEngine engine = new CorrectionEngine(jdbc, fakeStaging(), validator, new MockLlmClient());

        ProjectRules rules = new ProjectRules(List.of(),
                List.of(new ProjectRules.Rule("orders", "logistics_no", "CUSTOM_SQL",
                        "已发货订单必须有物流单号", 0.9, ProjectRules.Rule.Source.AGENT, Map.of(
                        "check_sql", "SELECT t.`id` AS `id` FROM {schema}.orders t"
                                + " WHERE t.`status` >= 2 AND (t.`logistics_no` IS NULL OR t.`logistics_no` = '')",
                        "fix_sql", "DELETE FROM {schema}.orders WHERE id = {id}"))),
                List.of(), 1);
        CorrectionAgent.CorrectionResult result = engine.run(
                new CorrectionAgent.CorrectionRequest(tables, Map.of(), customReport(2), rules, "shop", null),
                5);

        assertFalse(result.converged(), "坏 fix_sql 应上报残留不收敛");
        assertTrue(jdbc.executed.stream().noneMatch(s -> s.startsWith("DELETE")),
                "坏 fix_sql 不应被执行");
    }
}
