package com.dataforge.derivation;

import com.dataforge.model.ColumnDef;
import com.dataforge.model.DataRow;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.SourceTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通用聚合回填器单测（derive-universal-plan.md T4 验收）。 */
class ReconcileEngineTest {

    // ---- 夹具 ----

    private static ColumnDef col(String name, String type, boolean pk) {
        return new ColumnDef(name, type, false, pk, false, "", List.of());
    }

    private static DataRow row(Object id, Map<String, Object> extra, Map<String, SourceTag> lineage) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        if (extra != null) values.putAll(extra);
        return new DataRow(values, lineage);
    }

    private static GeneratedData orders() {
        List<DataRow> rows = new ArrayList<>();
        for (long i = 1; i <= 3; i++) {
            Map<String, SourceTag> lineage = new LinkedHashMap<>();
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("total_amount", null);   // 占位，待回填
            lineage.put("total_amount", SourceTag.EXPAND);
            rows.add(row(i, extra, lineage));
        }
        return new GeneratedData("orders",
                List.of(col("id", "BIGINT", true), col("total_amount", "DECIMAL(10,2)", false)),
                rows);
    }

    private static GeneratedData orderItem() {
        List<DataRow> rows = new ArrayList<>();
        rows.add(row(1L, Map.of("order_id", 1L, "subtotal", 10.00), new LinkedHashMap<>()));
        rows.add(row(2L, Map.of("order_id", 1L, "subtotal", 20.00), new LinkedHashMap<>()));
        rows.add(row(3L, Map.of("order_id", 2L, "subtotal", 5.00), new LinkedHashMap<>()));
        return new GeneratedData("order_item",
                List.of(col("id", "BIGINT", true), col("order_id", "BIGINT", false),
                        col("subtotal", "DECIMAL(10,2)", false)),
                rows);
    }

    private static ProjectRules.Rule agg() {
        return ProjectRules.Rule.aggEqual("orders", "total_amount", "order_item", "subtotal",
                "order_id", "总额 = Σ 明细小计", ProjectRules.Rule.Source.USER_CONFIRMED);
    }

    // ---- 合法 ----

    @Test
    void 按规则聚合回填_命中组填sum_无组填0() {
        Map<String, GeneratedData> data = new LinkedHashMap<>();
        data.put("orders", orders());
        data.put("order_item", orderItem());
        boolean applied = ReconcileEngine.apply(data, List.of(agg()));
        assertTrue(applied, "存在可应用规则应返回 true");

        GeneratedData orders = data.get("orders");
        // id1: 10+20=30.00；id2: 5.00；id3: 无明细 → 0.00
        assertEquals(0, toBd(orders.rows().get(0).values().get("total_amount")).compareTo(new java.math.BigDecimal("30.00")),
                "订单 1 总额应 = 30.00");
        assertEquals(0, toBd(orders.rows().get(1).values().get("total_amount")).compareTo(new java.math.BigDecimal("5.00")));
        assertEquals(0, toBd(orders.rows().get(2).values().get("total_amount")).compareTo(new java.math.BigDecimal("0.00")));
        // 血缘 CORRECTED
        assertEquals(SourceTag.CORRECTED, orders.rows().get(0).lineage().get("total_amount"));
    }

    @Test
    void ref表不在生成集_跳过返回false不抛() {
        Map<String, GeneratedData> data = new LinkedHashMap<>();
        data.put("orders", orders());   // 无 order_item
        assertFalse(ReconcileEngine.apply(data, List.of(agg())));
    }

    @Test
    void 空规则或null_返回false() {
        Map<String, GeneratedData> data = Map.of("orders", orders());
        assertFalse(ReconcileEngine.apply(data, List.of()));
        assertFalse(ReconcileEngine.apply(data, null));
        assertFalse(ReconcileEngine.apply(null, List.of(agg())));
    }

    @Test
    void 非AGG规则被忽略() {
        Map<String, GeneratedData> data = new LinkedHashMap<>();
        data.put("orders", orders());
        data.put("order_item", orderItem());
        ProjectRules.Rule other = ProjectRules.Rule.simple("orders", "status", "ENUM_WEIGHT",
                "状态权重", 0.8, ProjectRules.Rule.Source.AGENT);
        boolean applied = ReconcileEngine.apply(data, List.of(other));
        assertFalse(applied);
        assertNull(data.get("orders").rows().get(0).values().get("total_amount"), "total 应保持未填");
    }

    // ---- EXPR_EQUAL（行内表达式回填，docs/tech-debt-design.md 步骤 1） ----

    /** 三行订单，金额列含 total/discount/freight/actual（actual 为占位 0） */
    private static GeneratedData amounts() {
        List<ColumnDef> cols = List.of(
                col("id", "BIGINT", true),
                col("total_amount", "DECIMAL(10,2)", false),
                col("discount_amount", "DECIMAL(10,2)", false),
                col("freight", "DECIMAL(10,2)", false),
                col("actual_amount", "DECIMAL(10,2)", false));
        List<DataRow> rows = new ArrayList<>();
        rows.add(amountRow(1L, 0.0, 10.00, 12.00));
        rows.add(amountRow(2L, 0.0, 0.0, 0.0));
        rows.add(amountRow(3L, 0.0, 0.0, 0.0));
        return new GeneratedData("orders", cols, rows);
    }

    private static DataRow amountRow(long id, double total, double discount, double freight) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", id);
        v.put("total_amount", total);       // 占位，待 AGG 回填
        v.put("discount_amount", discount);
        v.put("freight", freight);
        v.put("actual_amount", 0.0);        // 占位，待 EXPR 回填
        return new DataRow(v, new LinkedHashMap<>());
    }

    /** actual_amount = total_amount - discount_amount + freight */
    private static ProjectRules.Rule expr() {
        return new ProjectRules.Rule("orders", "actual_amount", "EXPR_EQUAL",
                "实付金额 = 订单金额 - 优惠金额 + 运费", 1.0,
                ProjectRules.Rule.Source.USER_OVERRIDE,
                Map.of("expr", "total_amount - discount_amount + freight"));
    }

    @Test
    void 行内表达式回填_引用AGG结果_逐行等式成立() {
        Map<String, GeneratedData> data = new LinkedHashMap<>();
        data.put("orders", amounts());
        data.put("order_item", orderItem());
        assertTrue(ReconcileEngine.apply(data, List.of(agg(), expr())));

        GeneratedData orders = data.get("orders");
        // total 由 AGG 回填：30.00 / 5.00 / 0.00 → actual = total - discount + freight
        assertBd("32.00", orders.rows().get(0).values().get("actual_amount"), "订单1: 30-10+12");
        assertBd("5.00", orders.rows().get(1).values().get("actual_amount"), "订单2: 5-0+0");
        assertBd("0.00", orders.rows().get(2).values().get("actual_amount"), "订单3: 0-0+0");
        assertEquals(SourceTag.CORRECTED, orders.rows().get(0).lineage().get("actual_amount"));
    }

    @Test
    void 规则顺序无关_EXPR在AGG之前仍正确() {
        Map<String, GeneratedData> data = new LinkedHashMap<>();
        data.put("orders", amounts());
        data.put("order_item", orderItem());
        // 故意把 EXPR_EQUAL 排在 AGG_EQUAL 前面：两遍执行应保证 AGG 先算
        ReconcileEngine.apply(data, List.of(expr(), agg()));
        assertBd("32.00", data.get("orders").rows().get(0).values().get("actual_amount"),
                "顺序颠倒也应拿到 AGG 回填后的 total");
    }

    @Test
    void EXPR引用列不存在_抛异常不静默() {
        Map<String, GeneratedData> data = new LinkedHashMap<>();
        data.put("orders", amounts());
        ProjectRules.Rule bad = new ProjectRules.Rule("orders", "actual_amount", "EXPR_EQUAL",
                "引用了不存在的列", 1.0, ProjectRules.Rule.Source.USER_OVERRIDE,
                Map.of("expr", "total_amount - not_a_column"));
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> ReconcileEngine.apply(data, List.of(bad)));
        assertTrue(e.getMessage().contains("not_a_column"), "异常应点名缺失列: " + e.getMessage());
    }

    @Test
    void 混合规则列表_调用侧原样下传_两类都生效其余跳过() {
        // 调用侧不再预筛类型（DefaultGenerationAgent 原样传 correctionRules），
        // 引擎必须自己挑出两类、静默跳过其余——漏筛是静默失败，这里锁死
        Map<String, GeneratedData> data = new LinkedHashMap<>();
        data.put("orders", amounts());
        data.put("order_item", orderItem());
        List<ProjectRules.Rule> mixed = List.of(
                ProjectRules.Rule.simple("orders", "status", "CUSTOM_SQL", "自定义校验", 0.9,
                        ProjectRules.Rule.Source.AGENT),
                agg(),
                ProjectRules.Rule.simple("orders", "pay_time", "STATUS_FLOW", "时间线", 0.9,
                        ProjectRules.Rule.Source.AGENT),
                expr());
        assertTrue(ReconcileEngine.apply(data, mixed), "混合列表中 AGG 应生效");
        assertBd("32.00", data.get("orders").rows().get(0).values().get("actual_amount"),
                "混合列表中 EXPR 也应生效");
    }

    @Test
    void 仅EXPR规则_返回值不冒充AGG已接管() {
        Map<String, GeneratedData> data = new LinkedHashMap<>();
        data.put("orders", amounts());
        // 无 order_item → AGG 无法回填；EXPR 成功也不得让返回值变 true
        // （该返回值专指"跨表聚合已接管"；混入行内表达式的成功会让调用方误判 total 已有着落）
        assertFalse(ReconcileEngine.apply(data, List.of(expr())),
                "EXPR 的成功语义不是 total 已被接管");
    }

    private static void assertBd(String expected, Object actual, String message) {
        assertEquals(0, toBd(actual).compareTo(new java.math.BigDecimal(expected)), message);
    }

    private static java.math.BigDecimal toBd(Object v) {
        return v instanceof Number n ? new java.math.BigDecimal(n.toString())
                : (java.math.BigDecimal) v;
    }
}
