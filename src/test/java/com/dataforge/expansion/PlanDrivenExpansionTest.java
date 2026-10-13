package com.dataforge.expansion;

import com.dataforge.config.GeneratorProperties;
import com.dataforge.derivation.ReconcileEngine;
import com.dataforge.expansion.blueprint.DerivedTableExpander;
import com.dataforge.llm.MockLlmClient;
import com.dataforge.model.DataRow;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.GenerationPlan;
import com.dataforge.model.PipelineContext;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;
import com.dataforge.parser.DdlParser;
import com.dataforge.pipeline.ContentGen;
import com.dataforge.pipeline.FkAlloc;
import com.dataforge.pipeline.PkGen;
import com.dataforge.validation.DataValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 计划驱动路径的展开测试——补齐<b>生产路径的测试空白</b>。
 *
 * <p>背景：既有测试全部落在非生产路径上——{@code ExpansionSmokeTest} 走"无计划 → 内置展开器"
 * 的 fallback，{@code PipelineSmokeTest} 更是把 expansion 传 null 直接走 LLM 分支。
 * 而生产运行由落盘的 {@code generation-plan.json} 驱动。这个空白导致 7 个内置展开器里有 5 个
 * 在常态下早已不被调用，却长期无人察觉（见 docs/tech-debt-design.md 步骤 3）。</p>
 *
 * <p>计划直接读实物 {@code generation-plan.json}：该文件不入库，缺失时跳过（{@code assumeTrue}），
 * 避免 CI 失败；在开发机上则测的是真实计划，而非会漂移的手抄副本。</p>
 */
class PlanDrivenExpansionTest {

    private static final String SHOP_DDL = "schema/shop_schema.sql";
    private static final Path PLAN_FILE = Path.of("generation-plan.json");

    private static List<TableMeta> shopTables;

    @BeforeAll
    static void parseShopDdl() throws Exception {
        try (InputStream in = PlanDrivenExpansionTest.class.getResourceAsStream("/" + SHOP_DDL)) {
            if (in == null) throw new IllegalStateException("测试资源缺失: " + SHOP_DDL);
            shopTables = new DdlParser().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).tables();
        }
    }

    private static GenerationPlan realPlan() throws Exception {
        assumeTrue(Files.exists(PLAN_FILE), "本地 " + PLAN_FILE + " 未生成（不入库），跳过计划驱动测试");
        return new ObjectMapper().readValue(PLAN_FILE.toFile(), GenerationPlan.class);
    }

    /** 闭包：指定表 + FK 父表（与 ExpansionSmokeTest 同口径） */
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

    /** 跑一次计划驱动生成（唯一与既有测试的差异：context.setPlan） */
    private static Map<String, GeneratedData> generate(GenerationPlan plan,
                                                       Map<String, Integer> requested) {
        List<TableMeta> tables = closure(requested.keySet());
        PipelineContext context = new PipelineContext(tables, rowCounts(tables, requested), 42L);
        context.setPlan(plan);
        PkGen.execute(context, PkGen.Strategy.SEQUENTIAL);
        FkAlloc.execute(context);
        ContentGen.GenOutcome outcome = ContentGen.execute(context, new MockLlmClient(),
                new GeneratorProperties(false, 42L, 50, 4, "sequential", null, 5, 10), null, expansionEngine());
        assertTrue(outcome.failedTables().isEmpty(), "不应有失败表: " + outcome.failedTables());
        return outcome.results().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().data()));
    }

    private static final Map<String, Integer> CART_LINK =
            Map.of("user", 20, "product", 20, "sku", 80, "cart", 100);

    @Test
    void 计划驱动的cart_行数达标且父引用无孤儿() throws Exception {
        Map<String, GeneratedData> data = generate(realPlan(), CART_LINK);

        assertEquals(100, data.get("cart").rowCount(), "cart 行数应精确达标（与 rowCount 权威一致）");

        DataValidator.ValidationSummary summary = DataValidator.validate(
                closure(CART_LINK.keySet()).stream().toList(), data, Set.of());
        assertEquals(0, summary.fkOrphanCount(), "cart 的 user_id/product_id/sku_id 不应悬挂");
    }

    @Test
    void 结构化列_JSON形态合法且不经LLM() throws Exception {
        // specs / images / detail 原先走 LLM 生成内嵌 JSON——转义不可靠会撑破外层结构，
        // 实测会让整表失败。改由本地确定性构造后，形态必须始终是合法 JSON / 可控文本。
        Map<String, Integer> requested = Map.of("category", 5, "product", 20, "sku", 60);
        Map<String, GeneratedData> data = generate(realPlan(), requested);

        ObjectMapper om = new ObjectMapper();
        for (DataRow row : data.get("sku").rows()) {
            String specs = String.valueOf(row.values().get("specs"));
            JsonNode node = om.readTree(specs);   // 非法 JSON 在此抛，测试即失败
            assertTrue(node.isObject(), "specs 应是 JSON 对象，实际: " + specs);
            assertTrue(node.has("color") && node.has("size"), "specs 应含 color/size: " + specs);
        }
        for (DataRow row : data.get("product").rows()) {
            String images = String.valueOf(row.values().get("images"));
            JsonNode arr = om.readTree(images);
            assertTrue(arr.isArray(), "images 应是 JSON 数组，实际: " + images);
            assertTrue(arr.size() >= 3, "轮播图至少 3 张，实际 " + arr.size());

            String detail = String.valueOf(row.values().get("detail"));
            assertTrue(detail.contains("<p>") && detail.contains("</p>"),
                    "detail 应是富文本段落，实际: " + detail);
        }
    }

    @Test
    void 去掉对账兜底后_金额链仍由规则自洽() throws Exception {
        // 步骤 4 的前置验收：DefaultGenerationAgent 不再调 Reconciliation 之后，
        // subtotal（展开器自算）+ total（AGG_EQUAL）+ actual（EXPR_EQUAL）三段必须仍然成立。
        // 注意这里**故意不调 Reconciliation**——它就是被删掉的那层兜底。
        Map<String, Integer> requested = Map.of("user", 20, "product", 20, "sku", 80,
                "orders", 100, "order_item", 250);
        Map<String, GeneratedData> data = generate(realPlan(), requested);

        boolean aggApplied = ReconcileEngine.apply(
                data, ProjectRules.withDefaultCorrectionRules().correctionRules());
        assertTrue(aggApplied, "AGG_EQUAL 应接管 total_amount");

        GeneratedData orders = data.get("orders");
        GeneratedData orderItem = data.get("order_item");

        // ① 行内：subtotal = price × quantity（OrderItemExpander 自算）
        for (DataRow row : orderItem.rows()) {
            double price = ((Number) row.values().get("price")).doubleValue();
            int qty = ((Number) row.values().get("quantity")).intValue();
            assertEquals(Math.round(price * qty * 100.0) / 100.0,
                    ((Number) row.values().get("subtotal")).doubleValue(), 0.001,
                    "subtotal 应 = price × quantity");
        }

        // ② 跨表：total_amount = Σ subtotal（按 order_id 分组，AGG_EQUAL 回填）
        Map<Object, Double> sumByOrder = new HashMap<>();
        for (DataRow row : orderItem.rows()) {
            sumByOrder.merge(row.values().get("order_id"),
                    ((Number) row.values().get("subtotal")).doubleValue(), Double::sum);
        }
        for (DataRow row : orders.rows()) {
            Double expected = sumByOrder.getOrDefault(row.values().get("id"), 0.0);
            assertEquals(Math.round(expected * 100.0) / 100.0,
                    ((Number) row.values().get("total_amount")).doubleValue(), 0.011,
                    "total_amount 应 = Σ 明细小计");
        }

        // ③ 行内：actual_amount = total - discount + freight（EXPR_EQUAL 回填，引用 ② 的结果）
        for (DataRow row : orders.rows()) {
            double total = ((Number) row.values().get("total_amount")).doubleValue();
            double discount = ((Number) row.values().get("discount_amount")).doubleValue();
            double freight = ((Number) row.values().get("freight")).doubleValue();
            assertEquals(Math.round((total - discount + freight) * 100.0) / 100.0,
                    ((Number) row.values().get("actual_amount")).doubleValue(), 0.001,
                    "actual_amount 应 = total - discount + freight");
        }
    }

    @Test
    void 计划驱动的cart_条目商品应与SKU所属商品一致() throws Exception {
        // CartExpander 的口径：product_id 取自该 SKU 所属商品的 product_id（两者天然一致）。
        // 计划把 product_id 与 sku_id 声明成两个独立的 fk + zipf 随机引用——若引擎不感知这层
        // 从属关系，同一行的 product_id 就可能指向别的商品，购物车条目自相矛盾。
        Map<String, GeneratedData> data = generate(realPlan(), CART_LINK);

        Map<Object, Object> skuToProduct = new LinkedHashMap<>();
        for (DataRow row : data.get("sku").rows()) {
            skuToProduct.put(row.values().get("id"), row.values().get("product_id"));
        }

        int mismatched = 0;
        Object firstBad = null;
        for (DataRow row : data.get("cart").rows()) {
            Object skuId = row.values().get("sku_id");
            Object productId = row.values().get("product_id");
            Object expected = skuToProduct.get(skuId);
            if (expected != null && !expected.equals(productId)) {
                mismatched++;
                if (firstBad == null) firstBad = "cart.sku_id=" + skuId + " → sku.product_id="
                        + expected + "，但 cart.product_id=" + productId;
            }
        }
        assertEquals(0, mismatched, "cart 条目的 product_id 必须等于该 SKU 所属商品的 id；"
                + "共 " + mismatched + " 行不一致，首例: " + firstBad);
    }
}
