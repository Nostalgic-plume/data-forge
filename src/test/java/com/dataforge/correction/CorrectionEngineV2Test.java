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
 * 修正引擎 V2 单测：新规则类型桶（VALUE_RANGE）走规则通道（零 LLM 批量 SQL）+ 收敛闭环。
 */
class CorrectionEngineV2Test {

    /** 可编程 JDBC：VALUE_RANGE check 首次返回问题行（之后空 = 修正生效），其余查询空 */
    private static class FakeJdbc implements JdbcDataSource {
        final List<String> executed = new ArrayList<>();
        private boolean valueRangeChecked = false;

        @Override
        public List<Map<String, Object>> query(String sql, Object... params) {
            if (sql.contains("`total_amount`") && sql.contains("< 0") && !valueRangeChecked) {
                valueRangeChecked = true;
                return List.of(Map.of("id", 1L, "value", 150000.0));   // 超上界的问题行
            }
            return List.of();
        }

        @Override
        public int execute(String sql, Object... params) {
            executed.add(sql);
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
}
