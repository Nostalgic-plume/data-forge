package com.dataforge.correction;

import com.dataforge.agent.CorrectionAgent;
import com.dataforge.db.JdbcDataSource;
import com.dataforge.db.StagingManager;
import com.dataforge.llm.MockLlmClient;
import com.dataforge.model.*;
import com.dataforge.parser.DdlParser;
import com.dataforge.validation.MetaRuleCompiler;
import com.dataforge.validation.SqlValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 修正 agent 单测（mock JdbcDataSource，不连真实库）：
 * P3 验收——金额错误自动收敛为 0 + 同批修正不互相覆盖 + 回归回滚 + FK 重分配。
 */
class CorrectionAgentTest {

    /** shop 18 表 DDL（测试资源，自包含不依赖外部仓库） */
    private static final String SHOP_DDL = "schema/shop_schema.sql";
    private static TableMeta orders;

    @BeforeAll
    static void parseShopDdl() throws Exception {
        orders = new DdlParser().parse(readShopDdl()).tables().stream()
                .filter(t -> t.name().equals("orders")).findFirst().orElseThrow();
    }

    private static String readShopDdl() throws Exception {
        try (var in = CorrectionAgentTest.class.getResourceAsStream("/" + SHOP_DDL)) {
            if (in == null) throw new IllegalStateException("测试资源缺失: " + SHOP_DDL);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 状态化 mock JDBC：按 SQL 片段返回预设行，模拟"修正生效"（再校验转绿） */
    private static class FakeJdbc implements JdbcDataSource {
        final List<String> executed = new ArrayList<>();      // 记录 execute 的 SQL（断言用）
        boolean amountFixed = false;                          // 金额修正后 → 金额 check 转绿
        List<Map<String, Object>> fkOrphans = List.of();      // FK 悬挂（修正后清空）
        List<Map<String, Object>> readBackRow = List.of(Map.of(
                "id", 1L, "total_amount", 100.0, "user_id", 999L));

        @Override
        public List<Map<String, Object>> query(String sql, Object... params) {
            if (sql.startsWith("SELECT *")) return readBackRow;                       // readBack 读回
            if (sql.contains("SUM(`subtotal`)")) {                                     // 金额 check/校验
                return amountFixed ? List.of()
                        : List.of(Map.of("id", 1L, "current", 50.0, "expected", 100.0));
            }
            if (sql.contains("ROW_NUMBER()")) return List.of();                        // 唯一去重（本测试未用）
            if (sql.contains("AS `pk`")) {                                             // FK 父表 PK 池
                return List.of(Map.of("pk", 1L), Map.of("pk", 2L), Map.of("pk", 3L));
            }
            if (sql.contains("COUNT(*) AS c") || sql.contains("COUNT(*) AS `c`")) {    // 行数统计
                return List.of(Map.of("c", 1L));
            }
            if (sql.contains("LEFT JOIN")) return fkOrphans;                           // FK 悬挂
            return List.of();
        }

        @Override
        public int execute(String sql, Object... params) {
            executed.add(sql);
            if (sql.contains("`total_amount`") && sql.startsWith("UPDATE")) {
                amountFixed = true;         // 金额重算生效 → 再校验金额转绿
            }
            if (sql.contains("`user_id`")) {
                fkOrphans = List.of();      // FK 重分配生效 → 再校验 FK 转绿
            }
            return 1;
        }

        @Override
        public long maxId(String schema, String table) { return 0; }

        @Override
        public void transaction(Supplier<Void> work) { work.get(); }
    }

    private static class FakeStaging implements StagingManager {
        int dropCalls = 0, createCalls = 0, loadCalls = 0;

        @Override public String stagingSchema(String schemaName) { return schemaName + "_staging"; }
        @Override public void create(String schemaName, List<TableMeta> tables) { createCalls++; }
        @Override public void load(String schemaName, Map<String, GeneratedData> data) { loadCalls++; }
        @Override public Map<String, GeneratedData> read(String schemaName, List<TableMeta> tables) {
            return Map.of();
        }
        @Override public void drop(String schemaName) { dropCalls++; }
    }

    private static Map<String, GeneratedData> ordersData() {
        return Map.of("orders", new GeneratedData("orders", orders.columns(), List.of(
                new DataRow(Map.of("id", 1L, "total_amount", 50.0, "user_id", 999L), lineage("orders")))));
    }

    private static Map<String, SourceTag> lineage(String table) {
        Map<String, SourceTag> m = new LinkedHashMap<>();
        for (ColumnDef c : orders.columns()) m.put(c.name(), SourceTag.SEED);
        return m;
    }

    private static CorrectionAgent.CorrectionResult runLoop(FakeJdbc jdbc, FakeStaging staging,
                                                            List<Map<String, Object>> fkOrphans) {
        jdbc.fkOrphans = fkOrphans;
        SqlValidator validator = new SqlValidator(jdbc, new MetaRuleCompiler(), staging);
        CorrectionEngine engine = new CorrectionEngine(jdbc, staging, validator, new MockLlmClient());
        Map<String, GeneratedData> data = ordersData();
        ValidationReport initial = validator.validate(
                List.of(orders), ProjectRules.withDefaultCorrectionRules(), "shop");
        return engine.run(new CorrectionAgent.CorrectionRequest(
                List.of(orders), data, initial,
                ProjectRules.withDefaultCorrectionRules(), "shop", null), 10);
    }

    /** 验收 1：金额错误自动收敛为 0 */
    @Test
    void amountMismatchConvergesToZero() {
        FakeJdbc jdbc = new FakeJdbc();
        CorrectionAgent.CorrectionResult result = runLoop(jdbc, new FakeStaging(), List.of());

        assertTrue(result.converged(), "金额差异应被修正收敛");
        assertEquals(0, result.finalReport().totalErrors(), "最终报告硬错误应为 0");
        // 金额重算 SQL 被执行（受控批量）
        assertTrue(jdbc.executed.stream().anyMatch(
                        s -> s.startsWith("UPDATE") && s.contains("`total_amount`")),
                "应执行金额重算 UPDATE SQL");
        // 修正明细：id 锚定，目标值 = Σ subtotal
        assertEquals(1, result.appliedFixes().size());
        FixCommand fix = result.appliedFixes().get(0);
        assertEquals("orders", fix.table());
        assertEquals(1L, fix.id());
        assertEquals(100.0, ((Number) fix.fixes().get("total_amount")).doubleValue(),
                "修正值应为 Σ subtotal");
        // 修正后数据读回（staging 为 source of truth）
        assertEquals(100.0, ((Number) result.data().get("orders").rows().get(0).values()
                .get("total_amount")).doubleValue());
    }

    /** 验收 2：同批修正不互相覆盖（FK 重分配 + 金额重算命中同一行 id=1，串行应用互不影响） */
    @Test
    void sameRowFixesDoNotOverwriteEachOther() {
        FakeJdbc jdbc = new FakeJdbc();
        // FK 悬挂：orders.user_id=999 不存在（id=1）
        List<Map<String, Object>> fkOrphans = List.of(
                Map.of("id", 1L, "fk_value", 999L, "fk_col", "user_id"));
        CorrectionAgent.CorrectionResult result = runLoop(jdbc, new FakeStaging(), fkOrphans);

        assertTrue(result.converged(), "两个桶都应被修正且收敛");
        // 两个修正都应用（不互相覆盖）：FK 重分配 user_id + 金额重算 total_amount
        assertEquals(2, result.appliedFixes().size());
        Map<Long, Map<String, Object>> byId = new HashMap<>();
        for (FixCommand f : result.appliedFixes()) {
            byId.computeIfAbsent(f.id(), k -> new HashMap<>()).putAll(f.fixes());
        }
        Map<String, Object> fixes = byId.get(1L);
        assertNotNull(fixes, "id=1 的行应有修正");
        assertTrue(fixes.containsKey("user_id"), "FK 重分配应生效");
        assertTrue(fixes.containsKey("total_amount"), "金额重算应生效（未被 FK 修正覆盖）");
        // FK 重分配值来自父表 PK 池（确定性 index）
        int poolValue = ((Number) fixes.get("user_id")).intValue();
        assertTrue(List.of(1, 2, 3).contains(poolValue), "FK 修正值应指向存在的父 PK");
        // 两条受控 UPDATE 都执行
        assertEquals(2, jdbc.executed.size());
    }

    /** 验收 3：回归检测（修正引入首轮清单之外的新错误 → 回滚重建工作区） */
    @Test
    void regressionRollsBackWorkArea() {
        FakeJdbc jdbc = new FakeJdbc() {
            @Override
            public int execute(String sql, Object... params) {
                executed.add(sql);
                if (sql.contains("`total_amount`") && sql.startsWith("UPDATE")) {
                    amountFixed = true;
                    // 模拟修正副作用：引入 FK 新悬挂（首轮清单之外的新错误）
                    fkOrphans = List.of(Map.of("id", 9L, "fk_value", 998L, "fk_col", "user_id"));
                }
                return 1;
            }
        };
        FakeStaging staging = new FakeStaging();
        CorrectionAgent.CorrectionResult result = runLoop(jdbc, staging, List.of());

        assertTrue(result.rolledBack(), "引入新错误应触发回归回滚");
        assertFalse(result.converged());
        assertTrue(staging.loadCalls >= 1, "回滚应重载修正前快照");
        assertTrue(jdbc.executed.stream().anyMatch(s -> s.startsWith("DELETE")),
                "回滚应 DELETE 请求涉及的表（不 drop 整个库，流水线场景不误伤其他表）");
        assertEquals("orders", result.data().keySet().iterator().next(),
                "回滚后数据应为初始数据");
    }

    /** 验收 4：首轮校验全通过 → 无需修正，零轮循环 */
    @Test
    void cleanInitialReportSkipsLoop() {
        FakeJdbc jdbc = new FakeJdbc() {
            @Override
            public List<Map<String, Object>> query(String sql, Object... params) {
                if (sql.contains("SUM(`subtotal`)")) return List.of();  // 金额全通过
                return super.query(sql, params);
            }
        };
        CorrectionAgent.CorrectionResult result = runLoop(jdbc, new FakeStaging(), List.of());

        assertTrue(result.converged());
        assertEquals(0, result.iterations(), "全通过时循环零轮");
        assertTrue(result.appliedFixes().isEmpty());
    }
}
