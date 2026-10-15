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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        return generate(plan, requested, new MockLlmClient());
    }

    /** 同上，可注入带故障的 mock（用于验证补差等容错路径） */
    private static Map<String, GeneratedData> generate(GenerationPlan plan,
                                                       Map<String, Integer> requested,
                                                       MockLlmClient mock) {
        List<TableMeta> tables = closure(requested.keySet());
        PipelineContext context = new PipelineContext(tables, rowCounts(tables, requested), 42L);
        context.setPlan(plan);
        PkGen.execute(context, PkGen.Strategy.SEQUENTIAL);
        FkAlloc.execute(context);
        ContentGen.GenOutcome outcome = ContentGen.execute(context, mock,
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
    void 语义列按父表分组_父分类上下文喂给LLM() throws Exception {
        // 分类是 product 的父表：商品必须在分类之下生成。触发分组后每组带上"本批记录全部属于
        // 父分类 X"的提示词（否则整表一段 prompt，风格单一且与分类无关）。
        //
        // 注意行数下限：分组受 smallEnough 约束——父表行数必须 ≤ 本表 1/4。分类是固定的 30 个
        // 节点（不随请求变化），故 product 至少 120 行才会分组；这里取 160 留余量。
        // 这条耦合是真实约束而非测试噪声：行数不够时分组会被跳过（ContentGen 有 warn 留痕）。
        MockLlmClient mock = new MockLlmClient();
        Map<String, GeneratedData> data = generate(realPlan(),
                Map.of("category", 30, "product", 160), mock);

        assertTrue(mock.seenContexts().stream().anyMatch(c -> c.contains("属于分类")),
                "分组回填应把分类上下文给到 LLM，实际收到 " + mock.seenContexts().size() + " 条提示词");
        assertEquals(160, data.get("product").rowCount(), "分组不应影响行数");
    }

    @Test
    void llm偶发漏字段_补差重填而不是整表判死() throws Exception {
        // 真实事故复现：product 400 行时某个批次返回的行里 description 为 null → 原先直接整表失败。
        // LLM 偶发不完整输出是常态，为一行缺失把整表判死太脆；这里确定性逼出那条路径。
        MockLlmClient flaky = new MockLlmClient().blankOnFirstCall("product", "description");
        Map<String, Integer> requested = Map.of("category", 5, "product", 30);
        // generate 内部已断言"无失败表"——补差失效就会走成整表失败，此处直接红
        Map<String, GeneratedData> data = generate(realPlan(), requested, flaky);

        // 必须真走到补差：product 只有 1 批（30 行 / batch 30），补差重填会再来 1 次
        assertTrue(flaky.callsFor("product") >= 2,
                "应触发补差重填（首填 1 次 + 补差 1 次），实际 " + flaky.callsFor("product") + " 次");
        for (DataRow row : data.get("product").rows()) {
            assertNotNull(row.values().get("description"), "补差重填后不应再留空字段");
        }
    }

    @Test
    void 每个用户至多一条默认地址() throws Exception {
        // 校验规则会查这条：is_default 原先是独立 weighted 随机，同一用户会出现多条默认地址
        // （实测 77 行）。改为 chain path="1,0"——首条为 1、其余停在 0。
        Map<String, Integer> requested = Map.of("user", 20, "address", 60);
        Map<String, GeneratedData> data = generate(realPlan(), requested);

        Map<Object, Integer> defaultsPerUser = new HashMap<>();
        for (DataRow row : data.get("address").rows()) {
            if (((Number) row.values().get("is_default")).intValue() == 1) {
                defaultsPerUser.merge(row.values().get("user_id"), 1, Integer::sum);
            }
        }
        assertFalse(defaultsPerUser.isEmpty(), "应有默认地址");
        for (Map.Entry<Object, Integer> e : defaultsPerUser.entrySet()) {
            assertEquals(1, e.getValue(), "用户 " + e.getKey() + " 的默认地址应恰好 1 条");
        }
    }

    @Test
    void 状态日志按时间排列后首尾相接() throws Exception {
        // 校验规则查的就是这个：每条日志的开头必须等于上一条的结尾。
        // 原先 from_status / to_status 各自 weighted 随机 → 每行内部就不自洽、行间更接不上。
        Map<String, Integer> requested = Map.of("user", 15, "user_status_log", 45);
        Map<String, GeneratedData> data = generate(realPlan(), requested);

        Map<Object, List<DataRow>> byUser = new LinkedHashMap<>();
        for (DataRow row : data.get("user_status_log").rows()) {
            byUser.computeIfAbsent(row.values().get("user_id"), k -> new ArrayList<>()).add(row);
        }
        assertFalse(byUser.isEmpty(), "应有状态日志");

        int checked = 0;
        for (Map.Entry<Object, List<DataRow>> e : byUser.entrySet()) {
            List<DataRow> logs = e.getValue();
            // create_time 是 yyyy-MM-dd HH:mm:ss，字典序即时间序
            logs.sort(Comparator.comparing(r -> String.valueOf(r.values().get("create_time"))));
            for (int i = 1; i < logs.size(); i++) {
                assertEquals(logs.get(i - 1).values().get("to_status"),
                        logs.get(i).values().get("from_status"),
                        "用户 " + e.getKey() + " 第 " + i + " 条日志的开头应接上上一条的结尾");
                checked++;
            }
        }
        assertTrue(checked > 0, "至少应检查到一处衔接");
    }

    @Test
    void 全表计划驱动生成_引用与唯一键不变量成立() throws Exception {
        // 计划驱动路径此前零覆盖，补第一个测试（cart）时就炸出了 product_id 脱钩。
        // 这条用例把整条路径的所有表一次跑一遍，兜住同类问题。
        GenerationPlan plan = realPlan();
        Map<String, Integer> requested = Map.ofEntries(
                Map.entry("user", 15), Map.entry("category", 6), Map.entry("brand", 5),
                Map.entry("product", 15), Map.entry("sku", 45), Map.entry("shop", 5),
                Map.entry("address", 15), Map.entry("user_role", 15), Map.entry("user_status_log", 15),
                Map.entry("orders", 20), Map.entry("order_item", 50), Map.entry("cart", 30),
                Map.entry("chat_conversation", 10), Map.entry("chat_message", 25),
                Map.entry("chat_feedback", 10), Map.entry("role", 5), Map.entry("permission", 8),
                Map.entry("role_permission", 10));
        Map<String, GeneratedData> data = generate(plan, requested);

        assertTrue(data.size() >= 15, "应覆盖计划内的绝大多数表，实际 " + data.size() + " 张");

        DataValidator.ValidationSummary summary =
                DataValidator.validate(closure(requested.keySet()).stream().toList(), data, Set.of());
        assertEquals(0, summary.fkOrphanCount(),
                "全表生成后任何 FK 都不应悬挂（隐含：拓扑排序与父池引用整体正确）");
        assertEquals(0, summary.uniqueConflictCount(), "唯一键不应冲突");
    }

    @Test
    void 固定分类树_名称唯一且父子关系自洽() throws Exception {
        // 分类原先在 plan 里被拆成"每列各自随机"，同时踩了三个坑：
        //   ① name 走 pool → 独立抽样，30 个名池出 30 行必然重名；
        //   ② parent_id 走 valueRange 0~6 → "水果"可能挂到"家用电器"下，二级类目还可能浮到顶层；
        //   ③ status 写死 1 → 而 DDL 注释里 1 是"隐藏"，整库类目前端一个都看不见。
        // 三者都不报错（parent_id 非 FK、name 无 UNIQUE），是静默错。改为按 category-tree.json
        // 整表打表后，这三条不变量必须同时成立。
        Map<String, GeneratedData> data = generate(realPlan(), Map.of("category", 30));

        GeneratedData category = data.get("category");
        assertNotNull(category, "应生成 category");
        assertEquals(30, category.rowCount(), "分类树固定 30 个节点，行数不随请求变化");

        Map<Object, String> nameById = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();
        Map<Object, Object> parentById = new LinkedHashMap<>();
        for (DataRow row : category.rows()) {
            Object id = row.values().get("id");
            String name = String.valueOf(row.values().get("name"));
            assertTrue(names.add(name), "分类名必须唯一，重复: " + name);
            assertEquals(0, ((Number) row.values().get("status")).intValue(),
                    "status=1 在 DDL 里是『隐藏』，分类不该整库隐藏");
            nameById.put(id, name);
            parentById.put(id, row.values().get("parent_id"));
        }

        int roots = 0;
        int children = 0;
        for (Map.Entry<Object, Object> e : parentById.entrySet()) {
            long childId = ((Number) e.getKey()).longValue();
            long parentId = ((Number) e.getValue()).longValue();
            if (parentId == 0L) {
                roots++;
                continue;
            }
            assertTrue(nameById.containsKey(parentId),
                    "parent_id=" + parentId + " 应指向已生成的分类行，不得悬挂");
            assertTrue(parentId < childId,
                    "父分类应排在子分类之前（树按拓扑序打表）：子 id=" + childId + " 父 id=" + parentId);
            children++;
        }
        assertEquals(8, roots, "一级类目应为 8 个（parent_id=0）");
        assertEquals(30, roots + children, "每个分类非根即子，不应有第三态");
        assertTrue(children > 0, "应存在二级类目");
    }

    @Test
    void 商品的分类须是叶子类目_一级分类列由分类ID上溯得到() throws Exception {
        // DDL 明写：category_id 是"分类ID（所属二级分类）"、first_category_id 是"一级分类ID"。
        // 原配置前者在整棵树上取样（商品会挂到一级类目），后者写死 const 0（根本没有 id=0 的类目）——
        // 前者让商品分类的层级错乱，后者让"按一级类目筛选商品"整列失效。两处都不报错。
        Map<String, GeneratedData> data = generate(realPlan(),
                Map.of("category", 30, "product", 160));

        Map<Object, String> nameById = new LinkedHashMap<>();
        Map<Object, Object> parentById = new LinkedHashMap<>();
        Set<Object> hasChild = new HashSet<>();
        for (DataRow row : data.get("category").rows()) {
            Object id = row.values().get("id");
            Object pid = row.values().get("parent_id");
            nameById.put(id, String.valueOf(row.values().get("name")));
            parentById.put(id, pid);
            if (pid != null && ((Number) pid).longValue() != 0) hasChild.add(pid);
        }

        Set<Object> used = new LinkedHashSet<>();
        for (DataRow row : data.get("product").rows()) {
            Object catId = row.values().get("category_id");
            assertTrue(nameById.containsKey(catId), "category_id 应指向已生成的分类，实际 " + catId);
            assertFalse(hasChild.contains(catId), "category_id 必须是叶子类目（二级分类），"
                    + "却指向了有下级的一级类目「" + nameById.get(catId) + "」");
            used.add(catId);

            Object first = row.values().get("first_category_id");
            assertNotNull(first, "first_category_id 不该留空");
            long firstId = ((Number) first).longValue();
            assertNotEquals(0L, firstId, "不存在 id=0 的类目，该列写 0 就是无效值（原配置 const 0 即此错）");

            // 一级分类 = 沿 product.category_id 爬到根（循环条件"没有父为止"，不写死层级数）
            Object cur = catId;
            for (int hop = 0; hop < 16; hop++) {
                Object p = parentById.get(cur);
                if (p == null || ((Number) p).longValue() == 0) break;
                cur = p;
            }
            assertEquals(((Number) cur).longValue(), firstId,
                    "first_category_id 应是 category_id 所在分支的根，分类「" + nameById.get(catId) + "」");
        }
        assertTrue(used.size() > 1, "应覆盖多个分类，实际 " + used.size() + " 个");
    }

    @Test
    void 结构化列_JSON形态合法且不经LLM() throws Exception {
        // specs / images / detail 原先走 LLM 生成内嵌 JSON——转义不可靠会撑破外层结构，
        // 实测会让整表失败。改由本地确定性构造后，形态必须始终是合法 JSON / 可控文本。
        Map<String, Integer> requested = Map.of("category", 5, "product", 20, "sku", 60);
        Map<String, GeneratedData> data = generate(realPlan(), requested);

        ObjectMapper om = new ObjectMapper();
        for (DataRow row : data.get("sku").rows()) {
            Object raw = row.values().get("specs");
            // specs 已改为"按祖先分类选模板"（byAncestor）：mock 的分类名不含品类词，匹配不上
            // 就落空——这是预期行为，真实 LLM 下分类名（如"智能手机"）才匹配得上。
            // 但只要它有值，就必须是合法 JSON——这条守的正是原来那个坑（内嵌 JSON 撑破结构）。
            if (raw == null) continue;
            JsonNode node = om.readTree(String.valueOf(raw));
            assertTrue(node.isObject(), "specs 非空时必须是合法 JSON 对象，实际: " + raw);
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

        ReconcileEngine.apply(data, ProjectRules.withDefaultCorrectionRules().correctionRules());

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
