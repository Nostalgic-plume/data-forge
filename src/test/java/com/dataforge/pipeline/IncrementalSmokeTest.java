package com.dataforge.pipeline;

import com.dataforge.config.GeneratorProperties;
import com.dataforge.llm.MockLlmClient;
import com.dataforge.model.ColumnDef;
import com.dataforge.model.DataRow;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.SourceTag;
import com.dataforge.model.TableMeta;
import com.dataforge.parser.DdlParser;
import com.dataforge.validation.DataValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 增量生成冒烟测试（P5，mock LLM，不启动 Spring、不连库）：
 * 起始 ID=MAX+1（追加不冲突）+ FK 现有池引用（跨现有不悬挂）+ 现有数据保留。
 */
class IncrementalSmokeTest {

    /** shop 18 表 DDL（测试资源，自包含不依赖外部仓库） */
    private static final String SHOP_DDL = "schema/shop_schema.sql";
    private static List<TableMeta> shopTables;

    @BeforeAll
    static void parseShopDdl() throws Exception {
        shopTables = new DdlParser().parse(readShopDdl()).tables();
    }

    private static String readShopDdl() throws Exception {
        try (var in = IncrementalSmokeTest.class.getResourceAsStream("/" + SHOP_DDL)) {
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
        for (TableMeta t : tables) {
            counts.put(t.name(), requested.getOrDefault(t.name(), 5));
        }
        return counts;
    }

    /** 测试 1：PkGen 起始 ID=MAX+1（SEQUENTIAL 追加不冲突） */
    @Test
    void pkGenStartsFromExistingMaxPlusOne() {
        TableMeta user = shopTables.stream().filter(t -> t.name().equals("user")).findFirst().orElseThrow();
        PipelineContext ctx = new PipelineContext(List.of(user), Map.of("user", 3), 42L);
        ctx.setStartId("user", 6L);   // 现有 MAX(id)=5
        PkGen.execute(ctx, PkGen.Strategy.SEQUENTIAL);
        assertEquals(List.of(6L, 7L, 8L), ctx.pkSkeletons().get("user").get("id"),
                "SEQUENTIAL 应从现有 MAX+1 起，不冲突");
    }

    /** 测试 2：FkAlloc 现有池引用（父表池 = 现有 PK ∪ 新增 PK） */
    @Test
    void fkAllocReferencesExistingPlusNewPkPool() {
        List<TableMeta> tables = closure(Set.of("user", "orders"));
        Map<String, Integer> counts = rowCounts(tables, Map.of("user", 2, "orders", 3));
        PipelineContext ctx = new PipelineContext(tables, counts, 42L);
        ctx.setStartId("user", 6L);
        ctx.setExistingPool("user", List.of(1L, 2L, 3L, 4L, 5L));  // 现有 user
        PkGen.execute(ctx, PkGen.Strategy.SEQUENTIAL);
        FkAlloc.execute(ctx);

        List<Object> pool = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L);    // 现有 + 新增
        List<Object> userIds = ctx.fkAllocations().get("orders").get("user_id");
        assertEquals(3, userIds.size());
        for (Object v : userIds) {
            assertTrue(pool.contains(v), "FK 引用应落在现有 ∪ 新增池内（跨现有引用）");
        }
    }

    /** 测试 3：增量生成集成——新 ID 从 MAX+1、FK 跨现有不悬挂、现有行保留 */
    @Test
    void incrementalGenerationKeepsExistingAndAppendsNew() {
        List<TableMeta> tables = closure(Set.of("user", "orders", "order_item"));
        Map<String, Integer> newRows = rowCounts(tables, Map.of("user", 2, "orders", 3, "order_item", 6));

        // baseline：现有 user(1-5) / orders(1-5) / order_item(1-5)
        Map<String, GeneratedData> baseline = new LinkedHashMap<>();
        baseline.put("user", fakeTable("user", 5));
        baseline.put("orders", fakeTable("orders", 5));
        baseline.put("order_item", fakeTable("order_item", 5));

        PipelineContext ctx = new PipelineContext(tables, newRows, 42L);
        for (var e : baseline.entrySet()) {
            ctx.setStartId(e.getKey(), 6L);
            ctx.setExistingPool(e.getKey(), List.of(1L, 2L, 3L, 4L, 5L));
        }
        PkGen.execute(ctx, PkGen.Strategy.SEQUENTIAL);
        FkAlloc.execute(ctx);

        ContentGen.GenOutcome outcome = ContentGen.execute(ctx, new MockLlmClient(),
                new GeneratorProperties(false, 42L, 50, 4, "sequential", null, 5, 10),
                null, null, null, ContentGen.buildCompletedIndex(baseline));

        // 新增订单 id 从 6 起（现有 MAX=5，追加不冲突）
        List<Object> newOrderIds = outcome.results().get("orders").data().rows().stream()
                .map(r -> r.values().get("id")).toList();
        assertEquals(3, newOrderIds.size());
        for (Object id : newOrderIds) {
            assertTrue(((Number) id).longValue() >= 6L, "新增订单 id 应 ≥ MAX+1（追加不冲突）");
        }
        // 新增 user id 也从 6 起
        List<Object> newUserIds = outcome.results().get("user").data().rows().stream()
                .map(r -> r.values().get("id")).toList();
        for (Object id : newUserIds) {
            assertTrue(((Number) id).longValue() >= 6L, "新增 user id 应 ≥ MAX+1");
        }

        // 合并 = 现有 baseline + 新增（拼接，现有行保留）
        Map<String, GeneratedData> merged = new LinkedHashMap<>();
        for (TableMeta t : tables) {
            GeneratedData added = outcome.results().get(t.name()).data();
            if (added == null) continue;
            List<DataRow> allRows = new ArrayList<>();
            GeneratedData existing = baseline.get(t.name());
            if (existing != null) allRows.addAll(existing.rows());
            allRows.addAll(added.rows());
            merged.put(t.name(), new GeneratedData(t.name(), t.columns(), allRows));
        }
        // 现有行保留：orders 含 id=1..5 的现有行 + 新增
        List<Long> allOrderIds = merged.get("orders").rows().stream()
                .map(r -> ((Number) r.values().get("id")).longValue()).toList();
        assertEquals(8, allOrderIds.size());
        assertTrue(allOrderIds.containsAll(List.of(1L, 2L, 3L, 4L, 5L)), "现有订单行应保留");
        // 跨现有 FK 不悬挂（orders.user_id 引用现有 user 池 + 新增）
        DataValidator.ValidationSummary summary = DataValidator.validate(tables, merged, Set.of());
        assertEquals(0, summary.fkOrphanCount(), "FK 应跨现有引用有效（现有池 + 新增池）");
    }

    /** 构造一张最小现有表（PK + FK 列，供 baseline 池/DataValidator 引用） */
    private static GeneratedData fakeTable(String tableName, int n) {
        TableMeta meta = shopTables.stream().filter(t -> t.name().equals(tableName)).findFirst().orElseThrow();
        List<DataRow> rows = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", (long) i);
            // FK 列：让现有行引用有效（orders.user_id、order_item.order_id/product_id/sku_id）
            for (var fk : meta.foreignKeys()) {
                values.put(fk.column(), (long) i);
            }
            Map<String, SourceTag> lineage = new LinkedHashMap<>();
            for (ColumnDef c : meta.columns()) lineage.put(c.name(), SourceTag.SEED);
            rows.add(new DataRow(values, lineage));
        }
        return new GeneratedData(tableName, meta.columns(), rows);
    }
}
