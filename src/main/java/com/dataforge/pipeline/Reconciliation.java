package com.dataforge.pipeline;

import com.dataforge.model.DataRow;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.SourceTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨表确定性对账（legacy，规则执行非 LLM——信任模型红线）。
 *
 * <p><b>legacy 状态</b>：跨表 {@code total_amount} 段已被 {@code ReconcileEngine}（AGG_EQUAL 规则驱动）
 * 接管——生成侧有 AGG 规则时由 ReconcileEngine 先回填 total，本类在其后以
 * {@link #reconcile(Map, boolean)} 传入 {@code aggApplied=true} 跳过重复 total 段；
 * 无规则路径仍由本类全量兜底。行内 {@code subtotal} 与 {@code actual_amount}
 * （依赖 total）在订单域完整迁移到 expr 前仍在此。列名按业务语义探测（缺失列安全跳过）。</p>
 *
 * <p>表名/列名 orders/order_item/total_amount 为 legacy 业务字面量：订单域完整迁移到
 * 计划驱动的 expr + AGG 规则后，本类应整体移除（见 docs/derive-universal-design.md §H9）。</p>
 */
public final class Reconciliation {

    private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);

    private Reconciliation() {}

    /** 对账入口（legacy 签名：等价 aggApplied=false，全量兜底） */
    public static boolean reconcile(Map<String, GeneratedData> data) {
        return reconcile(data, false);
    }

    /**
     * 对账入口（就地修改 DataRow，血缘 CORRECTED）。
     *
     * @param data          各表生成数据
     * @param aggApplied    AGG_EQUAL 规则是否已由 ReconcileEngine 完成 total 回填；
     *                      true → 跳过跨表 total 段（避免 legacy 双份），false → 本方法全量兜底
     */
    public static boolean reconcile(Map<String, GeneratedData> data, boolean aggApplied) {
        GeneratedData orders = data.get("orders");
        GeneratedData orderItem = data.get("order_item");
        if (orders == null || orderItem == null) {
            log.info("金额对账跳过：orders 与 order_item 需同时生成（当前缺 {}）",
                    orders == null ? "orders" : "order_item");
            return false;
        }
        if (orders.rows().isEmpty() || orderItem.rows().isEmpty()) {
            log.info("金额对账跳过：orders/order_item 数据为空");
            return false;
        }

        // 列探测
        int totalAmountIdx = columnIndex(orders, "total_amount");
        int actualAmountIdx = columnIndex(orders, "actual_amount");
        int discountIdx = columnIndex(orders, "discount_amount");
        int freightIdx = columnIndex(orders, "freight");
        int orderIdIdx = columnIndex(orderItem, "order_id");
        int subtotalIdx = columnIndex(orderItem, "subtotal");
        int priceIdx = columnIndex(orderItem, "price");
        int quantityIdx = columnIndex(orderItem, "quantity");

        if (orderIdIdx < 0 || subtotalIdx < 0 || totalAmountIdx < 0) {
            log.warn("金额对账跳过：缺少必要列（order_id={}, subtotal={}, total_amount={})",
                    orderIdIdx, subtotalIdx, totalAmountIdx);
            return false;
        }
        String totalAmountCol = orders.columns().get(totalAmountIdx).name();

        // 1. 行内：subtotal = price × quantity（price/quantity 缺失时跳过该行；legacy：订单域迁移 expr 前仍在此）
        if (subtotalIdx >= 0 && priceIdx >= 0 && quantityIdx >= 0) {
            for (DataRow row : orderItem.rows()) {
                Object price = row.values().get(orderItem.columns().get(priceIdx).name());
                Object quantity = row.values().get(orderItem.columns().get(quantityIdx).name());
                if (price instanceof Number p && quantity instanceof Number q) {
                    setValue(row, orderItem.columns().get(subtotalIdx).name(),
                            BigDecimal.valueOf(p.doubleValue() * q.doubleValue()).setScale(2, RoundingMode.HALF_UP));
                }
            }
        }

        // 2. 跨表 total_amount = Σ(subtotal by order_id)：aggApplied=true 时已由 ReconcileEngine 接管，跳过
        if (!aggApplied) {
            Map<String, BigDecimal> orderTotals = new HashMap<>();
            for (DataRow row : orderItem.rows()) {
                Object orderId = row.values().get(orderItem.columns().get(orderIdIdx).name());
                Object subtotal = row.values().get(orderItem.columns().get(subtotalIdx).name());
                if (orderId == null) continue;
                BigDecimal add = subtotal instanceof Number s
                        ? BigDecimal.valueOf(s.doubleValue()) : BigDecimal.ZERO;
                orderTotals.merge(orderId.toString(), add, BigDecimal::add);
            }
            int idIdx = columnIndex(orders, "id");
            if (idIdx < 0) {
                log.warn("金额对账跳过：orders 表缺少 id 列");
                return false;
            }
            String idCol = orders.columns().get(idIdx).name();
            for (DataRow row : orders.rows()) {
                Object orderId = row.values().get(idCol);
                if (orderId == null) continue;
                BigDecimal total = orderTotals.getOrDefault(orderId.toString(), BigDecimal.ZERO)
                        .setScale(2, RoundingMode.HALF_UP);
                setValue(row, totalAmountCol, total);
            }
        }

        // 3. actual_amount = total_amount - discount_amount + freight（total 由规则接管或上段已回填）
        if (actualAmountIdx >= 0 && discountIdx >= 0 && freightIdx >= 0) {
            String discountCol = orders.columns().get(discountIdx).name();
            String freightCol = orders.columns().get(freightIdx).name();
            String actualCol = orders.columns().get(actualAmountIdx).name();
            for (DataRow row : orders.rows()) {
                Object total = row.values().get(totalAmountCol);
                Object discount = row.values().get(discountCol);
                Object freight = row.values().get(freightCol);
                BigDecimal t = total instanceof Number tn
                        ? BigDecimal.valueOf(tn.doubleValue()) : BigDecimal.ZERO;
                BigDecimal d = discount instanceof Number dn
                        ? BigDecimal.valueOf(dn.doubleValue()) : BigDecimal.ZERO;
                BigDecimal f = freight instanceof Number fn
                        ? BigDecimal.valueOf(fn.doubleValue()) : BigDecimal.ZERO;
                BigDecimal actual = t.subtract(d).add(f).setScale(2, RoundingMode.HALF_UP);
                setValue(row, actualCol, actual);
            }
        }

        log.info("金额对账完成: {} 个订单汇总 {} 条商品项（aggApplied={}）",
                orders.rowCount(), orderItem.rowCount(), aggApplied);
        return true;
    }

    /** 覆盖列值并标注血缘 CORRECTED */
    private static void setValue(DataRow row, String column, Object value) {
        row.values().put(column, value);
        row.lineage().put(column, SourceTag.CORRECTED);
    }

    private static int columnIndex(GeneratedData data, String column) {
        for (int i = 0; i < data.columns().size(); i++) {
            if (data.columns().get(i).name().equals(column)) return i;
        }
        return -1;
    }
}
