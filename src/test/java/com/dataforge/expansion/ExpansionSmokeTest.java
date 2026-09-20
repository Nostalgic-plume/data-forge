package com.dataforge.expansion;

import com.dataforge.cache.GenerationCache;
import com.dataforge.config.GeneratorProperties;
import com.dataforge.expansion.blueprint.DerivedTableExpander;
import com.dataforge.llm.MockLlmClient;
import com.dataforge.model.DataRow;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.SourceTag;
import com.dataforge.model.TableMeta;
import com.dataforge.model.PipelineContext;
import com.dataforge.parser.DdlParser;
import com.dataforge.pipeline.*;
import com.dataforge.validation.DataValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 展开器冒烟测试：两阶段生成（LLM 样本 + 规则展开）的正确性。
 *
 * <p>覆盖：展开行数精确达标、锚点派生（sku 价格比率）、FK 幂律长尾、
 * 金额对账一致、FK 无悬挂、血缘标注 EXPAND、样本预检。</p>
 */
class ExpansionSmokeTest {

    /** shop 18 表 DDL（测试资源，自包含不依赖外部仓库） */
    private static final String SHOP_DDL = "schema/shop_schema.sql";

    private static List<TableMeta> shopTables;

    @BeforeAll
    static void parseShopDdl() throws Exception {
        DdlParser.DdlParseResult parsed = new DdlParser().parse(readShopDdl());
        assertFalse(parsed.tables().isEmpty());
        shopTables = parsed.tables();
    }

    private static String readShopDdl() throws Exception {
        try (var in = ExpansionSmokeTest.class.getResourceAsStream("/" + SHOP_DDL)) {
            if (in == null) throw new IllegalStateException("测试资源缺失: " + SHOP_DDL);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<TableMeta> closure(Set<String> requested) {
        Map<String, TableMeta> all = shopTables.stream()
                .collect(Collectors.toMap(TableMeta::name, t -> t));
        Set<String> closure = new LinkedHashSet<>(requested);
        Deque<String> queue = new ArrayDeque<>(requested);
        while (!queue.isEmpty()) {
            TableMeta meta = all.get(queue.poll());
            if (meta == null) continue;
            for (var fk : meta.foreignKeys()) {
                if (all.containsKey(fk.refTable()) && closure.add(fk.refTable())) {
                    queue.add(fk.refTable());
                }
            }
        }
        return shopTables.stream().filter(t -> closure.contains(t.name())).toList();
    }

    private static Map<String, Integer> rowCounts(List<TableMeta> tables, Map<String, Integer> requested) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TableMeta t : tables) counts.put(t.name(), requested.getOrDefault(t.name(), 5));
        return counts;
    }

    private static ExpansionEngine expansionEngine() {
        return new ExpansionEngine(List.of(
                new DerivedTableExpander(ExpanderRegistry.skuConfig()),
                new DerivedTableExpander(ExpanderRegistry.addressConfig()),
                new DerivedTableExpander(ExpanderRegistry.userRoleConfig()),
                new DerivedTableExpander(ExpanderRegistry.shopConfig()),
                new OrdersExpander(), new OrderItemExpander(), new CartExpander()),
                new GenericExpander());
    }

    private record RunResult(ContentGen.GenOutcome outcome, Map<String, GeneratedData> data,
                             DataValidator.ValidationSummary summary) {
    }

    private static RunResult run(List<TableMeta> tables, Map<String, Integer> requested,
                                 GeneratorProperties props, MockLlmClient mock) {
        PipelineContext context = new PipelineContext(tables, rowCounts(tables, requested), props.seed());
        PkGen.execute(context, PkGen.Strategy.SEQUENTIAL);
        FkAlloc.execute(context);
        GenerationCache cache = props.cacheDir() != null
                ? new GenerationCache(Path.of(props.cacheDir())) : null;
        ContentGen.GenOutcome outcome = ContentGen.execute(context, mock, props, cache, expansionEngine());
        Map<String, GeneratedData> data = outcome.results().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().data()));
        Reconciliation.reconcile(data);
        DataValidator.ValidationSummary summary = DataValidator.validate(
                tables, data, Set.copyOf(outcome.failedTables()));
        return new RunResult(outcome, data, summary);
    }

    // =============================================================
    //  小规模两阶段：user20 / product20 / sku80 / orders100 / order_item250
    // =============================================================

    @Test
    void expansionRowsExactAndLinked() {
        List<TableMeta> tables = closure(Set.of("user", "product", "sku", "orders", "order_item"));
        Map<String, Integer> requested = Map.of("user", 20, "product", 20, "sku", 80,
                "orders", 100, "order_item", 250);
        RunResult r = run(tables, requested, new GeneratorProperties(false, 42L, 50, 4, "sequential", null, 5, 10),
                new MockLlmClient());

        // 展开表行数精确达标（零 LLM）
        assertEquals(80, r.outcome().results().get("sku").data().rowCount());
        assertEquals(100, r.outcome().results().get("orders").data().rowCount());
        assertEquals(250, r.outcome().results().get("order_item").data().rowCount());
        // LLM 表（user/product/category）走 LLM（llmBatches > 0），展开表零 LLM
        assertTrue(r.outcome().results().get("user").llmBatches() > 0, "user 是语义源表应走 LLM");
        assertEquals(0, r.outcome().results().get("orders").llmBatches(), "orders 是展开表应零 LLM");
        assertEquals(0, r.outcome().results().get("sku").llmBatches(), "sku 是展开表应零 LLM");

        // FK 无悬挂 + 金额对账一致
        assertEquals(0, r.summary().fkOrphanCount(), "FK 不应悬挂");
        assertTrue(r.summary().amountChecked(), "orders/order_item 同时生成应核对金额");
        assertEquals(0, r.summary().amountMismatchCount(), "展开 + 对账后金额应一致");
    }

    /** 锚点派生：sku.price > 0 且 original_price ≥ price（PostProcessor 兜底后） */
    @Test
    void anchorDerivedSkuPrice() {
        List<TableMeta> tables = closure(Set.of("user", "product", "sku", "orders"));
        Map<String, Integer> requested = Map.of("user", 20, "product", 20, "sku", 80, "orders", 100);
        RunResult r = run(tables, requested, new GeneratorProperties(false, 42L, 50, 4, "sequential", null, 5, 10),
                new MockLlmClient());

        GeneratedData sku = r.outcome().results().get("sku").data();
        for (DataRow row : sku.rows()) {
            double price = ((Number) row.values().get("price")).doubleValue();
            Object op = row.values().get("original_price");
            assertTrue(price > 0, "sku.price 应 > 0");
            if (op instanceof Number opNum) {
                assertTrue(opNum.doubleValue() >= price, "original_price ≥ price");
            }
        }
    }

    /** FK 幂律长尾：top 20% 用户的订单占比显著高于均匀（Zipf 生效） */
    @Test
    void fkZipfLongTail() {
        List<TableMeta> tables = closure(Set.of("user", "product", "sku", "orders", "order_item"));
        Map<String, Integer> requested = Map.of("user", 20, "product", 20, "sku", 80,
                "orders", 100, "order_item", 250);
        RunResult r = run(tables, requested, new GeneratorProperties(false, 42L, 50, 4, "sequential", null, 5, 10),
                new MockLlmClient());

        Map<String, Integer> orderPerUser = new HashMap<>();
        for (DataRow row : r.outcome().results().get("orders").data().rows()) {
            orderPerUser.merge(String.valueOf(row.values().get("user_id")), 1, Integer::sum);
        }
        // top 20% 用户（4/20）应占有明显超过 20% 的订单
        List<Integer> counts = orderPerUser.values().stream().sorted(Comparator.reverseOrder()).toList();
        int top4 = counts.subList(0, Math.min(4, counts.size())).stream().mapToInt(Integer::intValue).sum();
        assertTrue(top4 * 100 / 100 > 40, "top 20% 用户应占 >40% 订单（Zipf 长尾），实际 " + top4 + "/100");
        // 存在"多订单用户"（max 明显 > 均匀期望 5）
        assertTrue(!counts.isEmpty() && counts.get(0) > 8, "应有高频用户（max 订单 " + counts.get(0) + "）");
    }

    /** 订单项快照回填：order_item.order_no 与父订单一致、subtotal = price × quantity */
    @Test
    void orderItemSnapshotConsistency() {
        List<TableMeta> tables = closure(Set.of("user", "product", "sku", "orders", "order_item"));
        Map<String, Integer> requested = Map.of("user", 20, "product", 20, "sku", 80,
                "orders", 100, "order_item", 250);
        RunResult r = run(tables, requested, new GeneratorProperties(false, 42L, 50, 4, "sequential", null, 5, 10),
                new MockLlmClient());

        Map<String, String> orderNoById = new HashMap<>();
        for (DataRow row : r.outcome().results().get("orders").data().rows()) {
            orderNoById.put(String.valueOf(row.values().get("id")),
                    String.valueOf(row.values().get("order_no")));
        }
        for (DataRow row : r.outcome().results().get("order_item").data().rows()) {
            assertEquals(orderNoById.get(String.valueOf(row.values().get("order_id"))),
                    String.valueOf(row.values().get("order_no")), "order_no 快照应与父订单一致");
            double price = ((Number) row.values().get("price")).doubleValue();
            int quantity = ((Number) row.values().get("quantity")).intValue();
            double subtotal = ((Number) row.values().get("subtotal")).doubleValue();
            assertEquals(Math.round(price * quantity * 100.0) / 100.0, subtotal, 0.001, "subtotal = price × quantity");
        }
    }

    /** 血缘标注：展开行全部 EXPAND，对账回填 CORRECTED */
    @Test
    void expansionLineage() {
        List<TableMeta> tables = closure(Set.of("user", "product", "sku", "orders", "order_item"));
        Map<String, Integer> requested = Map.of("user", 20, "product", 20, "sku", 80,
                "orders", 100, "order_item", 250);
        RunResult r = run(tables, requested, new GeneratorProperties(false, 42L, 50, 4, "sequential", null, 5, 10),
                new MockLlmClient());

        DataRow skuRow = r.outcome().results().get("sku").data().rows().get(0);
        assertEquals(SourceTag.EXPAND, skuRow.lineage().get("price"), "展开列血缘应标 EXPAND");
        DataRow ordersRow = r.outcome().results().get("orders").data().rows().get(0);
        assertEquals(SourceTag.EXPAND, ordersRow.lineage().get("user_id"), "展开列血缘应标 EXPAND");
        assertEquals(SourceTag.CORRECTED, ordersRow.lineage().get("total_amount"),
                "对账回填列血缘应标 CORRECTED");
    }

    /** 固定样本表：role 内置 100/101/102（user_role 展开引用 code=user 角色） */
    @Test
    void fixedRoleSamples() {
        List<TableMeta> tables = closure(Set.of("user", "user_role"));
        Map<String, Integer> requested = Map.of("user", 20, "user_role", 20);
        RunResult r = run(tables, requested, new GeneratorProperties(false, 42L, 50, 4, "sequential", null, 5, 10),
                new MockLlmClient());

        GeneratedData role = r.outcome().results().get("role").data();
        assertEquals(3, role.rowCount());
        assertEquals(100L, ((Number) role.rows().get(0).values().get("id")).longValue());
        assertEquals("user", String.valueOf(role.rows().get(2).values().get("code")));
        // user_role 全部指向 user 角色（id=102）
        for (DataRow row : r.outcome().results().get("user_role").data().rows()) {
            assertEquals(102L, ((Number) row.values().get("role_id")).longValue());
        }
        assertEquals(0, r.summary().fkOrphanCount(), "user_role 的 FK 不应悬挂");
    }

    /** 唯一键确定性去重：重复 username 追加序号（zhangsan → zhangsan2） */
    @Test
    void uniqueKeyDeduper() {
        TableMeta user = shopTables.stream().filter(t -> t.name().equals("user")).findFirst().orElseThrow();
        List<DataRow> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", i + 1L);
            values.put("username", i < 3 ? "zhangsan" : "wangwei"); // 3 个重复
            values.put("nickname", "用户" + i);
            rows.add(new DataRow(values, Map.of()));
        }
        UniqueDeduper.dedupe(rows, user);

        Set<String> names = new HashSet<>();
        for (DataRow row : rows) names.add(String.valueOf(row.values().get("username")));
        assertEquals(5, names.size(), "去重后 username 应全部唯一");
        assertTrue(names.contains("zhangsan") && names.contains("zhangsan2")
                && names.contains("zhangsan3"), "重复值应追加序号");
    }

    /** 样本预检：预检规则本身（行数不足/唯一冲突 → 报错） */
    @Test
    void samplePreflightDetectsBadSample() {
        List<TableMeta> tables = closure(Set.of("user"));
        Map<String, Integer> requested = Map.of("user", 20);
        List<TableMeta> userTable = tables.stream().filter(t -> t.name().equals("user")).toList();
        PipelineContext context = new PipelineContext(userTable,
                rowCounts(userTable, requested), 42L);
        PkGen.execute(context, PkGen.Strategy.SEQUENTIAL);
        FkAlloc.execute(context);
        ContentGen.GenOutcome outcome = ContentGen.execute(context, new MockLlmClient(),
                new GeneratorProperties(false, 42L, 50, 4, "sequential", null, 5, 10),
                null, expansionEngine());

        assertTrue(outcome.results().containsKey("user"), "user 样本预检通过应正常生成");
        assertTrue(outcome.results().get("user").data().rowCount() >= 20);
    }
}
