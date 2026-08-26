package com.dataforge.correction;

import com.dataforge.agent.GenerationAgent;
import com.dataforge.agent.DefaultGenerationAgent;
import com.dataforge.config.GeneratorProperties;
import com.dataforge.db.JdbcDataSource;
import com.dataforge.db.StagingManager;
import com.dataforge.llm.MockLlmClient;
import com.dataforge.model.*;
import com.dataforge.parser.DdlParser;
import com.dataforge.pipeline.TableCompletedListener;
import com.dataforge.validation.MetaRuleCompiler;
import com.dataforge.validation.SqlValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 修正流水线单测（P4）：表完成事件触发修正 + 修正任务依赖图
 * （AGG_EQUAL 金额规则必须等 ref_table 落库后才修正 orders，小表修正不阻塞大表生成）。
 */
class CorrectionPipelineTest {

    /** shop 18 表 DDL（测试资源，自包含不依赖外部仓库） */
    private static final String SHOP_DDL = "schema/shop_schema.sql";
    private static TableMeta user, orders, orderItem;

    @BeforeAll
    static void parseShopDdl() throws Exception {
        List<TableMeta> tables = new DdlParser().parse(readShopDdl()).tables();
        user = tables.stream().filter(t -> t.name().equals("user")).findFirst().orElseThrow();
        orders = tables.stream().filter(t -> t.name().equals("orders")).findFirst().orElseThrow();
        orderItem = tables.stream().filter(t -> t.name().equals("order_item")).findFirst().orElseThrow();
    }

    private static String readShopDdl() throws Exception {
        try (var in = CorrectionPipelineTest.class.getResourceAsStream("/" + SHOP_DDL)) {
            if (in == null) throw new IllegalStateException("测试资源缺失: " + SHOP_DDL);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 状态化 mock JDBC：金额检查时记录当前已落库表集合（验证依赖图） */
    private static class FakeJdbc implements JdbcDataSource {
        final List<String> loadOrder;                                  // 共享引用（FakeStaging 写入）
        final List<List<String>> amountCheckSeenLoads = new CopyOnWriteArrayList<>();
        final List<String> executed = new CopyOnWriteArrayList<>();
        boolean amountFixed = false;

        FakeJdbc(List<String> loadOrder) { this.loadOrder = loadOrder; }

        @Override
        public List<Map<String, Object>> query(String sql, Object... params) {
            if (sql.startsWith("SELECT *")) return List.of(
                    Map.of("id", 1L, "total_amount", 100.0, "user_id", 1L));
            if (sql.contains("SUM(`subtotal`)")) {
                // 快照：金额检查发生时已完成落库的表（依赖图断言用）
                amountCheckSeenLoads.add(new ArrayList<>(loadOrder));
                return amountFixed ? List.of()
                        : List.of(Map.of("id", 1L, "current", 50.0, "expected", 100.0));
            }
            if (sql.contains("AS `pk`")) return List.of(Map.of("pk", 1L));
            if (sql.contains("LEFT JOIN") || sql.contains("GROUP BY") || sql.contains("ROW_NUMBER()")) {
                return List.of();
            }
            return List.of();
        }

        @Override
        public int execute(String sql, Object... params) {
            executed.add(sql);
            if (sql.startsWith("UPDATE") && sql.contains("`total_amount`")) amountFixed = true;
            return 1;
        }

        @Override public long maxId(String schema, String table) { return 0; }
        @Override public void transaction(Supplier<Void> work) { work.get(); }
    }

    private static class FakeStaging implements StagingManager {
        final List<String> loadOrder = new CopyOnWriteArrayList<>();
        int loadCalls = 0;

        @Override public String stagingSchema(String schemaName) { return schemaName + "_staging"; }
        @Override public void create(String schemaName, List<TableMeta> tables) { }
        @Override public void load(String schemaName, Map<String, GeneratedData> data) {
            loadCalls++;
            data.keySet().forEach(loadOrder::add);
        }
        @Override public Map<String, GeneratedData> read(String schemaName, List<TableMeta> tables) {
            return Map.of();
        }
        @Override public void drop(String schemaName) { }
    }

    /** 假生成 agent：按依赖序（user → orders → order_item）触发表完成事件，不真实生成 */
    private static DefaultGenerationAgent fakeAgent() {
        return new DefaultGenerationAgent(new MockLlmClient(),
                new GeneratorProperties(false, 42L, 50, 4, "sequential", null, 5, 10), null) {
            @Override
            public GenerationAgent.GenerationResult generate(GenerationAgent.GenerationRequest req,
                                                             boolean cacheEnabled, boolean expansionEnabled,
                                                             TableCompletedListener listener) {
                Map<String, GeneratedData> data = new LinkedHashMap<>();
                data.put("user", new GeneratedData("user", user.columns(), List.of(
                        new DataRow(Map.of("id", 1L, "username", "u1"), Map.of("id", SourceTag.PK)))));
                data.put("orders", new GeneratedData("orders", orders.columns(), List.of(
                        new DataRow(Map.of("id", 1L, "total_amount", 50.0, "user_id", 1L),
                                Map.of("id", SourceTag.PK)))));
                data.put("order_item", new GeneratedData("order_item", orderItem.columns(), List.of(
                        new DataRow(Map.of("id", 1L, "order_id", 1L, "subtotal", 100.0),
                                Map.of("id", SourceTag.PK)))));
                for (var e : data.entrySet()) {
                    listener.onTableCompleted(e.getKey(), e.getValue());  // user → orders → order_item
                }
                return new GenerationAgent.GenerationResult(data, List.of(), 0, Map.of(), Map.of());
            }
        };
    }

    /** P4 验收：金额修正依赖 order_item 落库后才触发（依赖图），小表不阻塞大表 */
    @Test
    void amountCorrectionWaitsForRefTableCompleted() {
        FakeStaging staging = new FakeStaging();
        FakeJdbc jdbc = new FakeJdbc(staging.loadOrder);
        SqlValidator validator = new SqlValidator(jdbc, new MetaRuleCompiler(), staging);
        CorrectionEngine engine = new CorrectionEngine(jdbc, staging, validator, new MockLlmClient());
        CorrectionPipeline pipeline = new CorrectionPipeline(staging, validator, engine,
                new GeneratorProperties(false, 42L, 50, 4, "sequential", null, 5, 10), fakeAgent());

        CorrectionPipeline.PipelineOutcome outcome = pipeline.run(
                "shop", List.of(user, orders, orderItem),
                ProjectRules.withDefaultCorrectionRules(),
                new GenerationAgent.GenerationRequest(List.of(user, orders, orderItem),
                        ProjectRules.withDefaultCorrectionRules(),
                        Map.of("user", 1, "orders", 1, "order_item", 1),
                        IncrementalConfig.full(), 42L, 50, 4),
                false, true);

        // 流水线结果（统计字段，验证修正确被触发）
        assertTrue(outcome.tablesFixed() >= 1, "流水线应修正过表");
        // ① 金额检查只在 order_item 落库之后发生（依赖图：AGG_EQUAL ref_table 就绪）
        assertFalse(jdbc.amountCheckSeenLoads.isEmpty(), "金额检查应发生（orders 修正触发）");
        for (List<String> seen : jdbc.amountCheckSeenLoads) {
            assertTrue(seen.contains("order_item"),
                    "金额检查时 order_item 必须已完成落库（修正任务依赖图）");
        }
        // ② orders 金额修正 UPDATE 被执行
        assertTrue(jdbc.executed.stream().anyMatch(
                        s -> s.startsWith("UPDATE") && s.contains("`total_amount`")),
                "orders 金额修正应执行");
        // ③ 三张表都落库（表完成事件驱动）
        assertEquals(3, staging.loadCalls, "每张表完成即落库");
        assertEquals(3, staging.loadOrder.size());
    }
}
