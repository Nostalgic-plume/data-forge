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

    private static java.math.BigDecimal toBd(Object v) {
        return v instanceof Number n ? new java.math.BigDecimal(n.toString())
                : (java.math.BigDecimal) v;
    }
}
