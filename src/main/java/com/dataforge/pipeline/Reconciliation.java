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
 * 跨表确定性对账（规则执行，非 LLM——信任模型红线）。
 *
 * <p>仅当 orders 与 order_item 同时生成（且都未失败）时执行：
 * <ol>
 *   <li>行内：order_item.subtotal = round(price × quantity, 2)</li>
 *   <li>跨表：orders.total_amount = Σ(order_item.subtotal by order_id)；
 *            orders.actual_amount = round(total_amount - discount_amount + freight, 2)</li>
 * </ol>
 * 覆盖值标注血缘 CORRECTED。order_item 未生成 → 跳过并提示（校验阶段不核对金额）。</p>
 *
 * <p>列名按业务语义探测（total_amount/actual_amount/discount_amount/freight/subtotal/price/quantity/order_id），
 * 找不到则跳过并提示——通用表结构也能安全运行。</p>
 */
public final class Reconciliation {

    private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);

    private Reconciliation() {}

    /** 对账入口：就地修改 DataRow 的 values（血缘标 CORRECTED）。 */
    public static boolean reconcile(Map<String, GeneratedData> data) {
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

        // 1. 行内：subtotal = price × quantity（price/quantity 缺失时跳过该行）
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

        // 2. 跨表：total_amount = Σ subtotal by order_id
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
        String totalAmountCol = orders.columns().get(totalAmountIdx).name();
        for (DataRow row : orders.rows()) {
            Object orderId = row.values().get(idCol);
            if (orderId == null) continue;
            BigDecimal total = orderTotals.getOrDefault(orderId.toString(), BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);
            setValue(row, totalAmountCol, total);

            // actual = total - discount + freight
            if (actualAmountIdx >= 0 && discountIdx >= 0 && freightIdx >= 0) {
                Object discount = row.values().get(orders.columns().get(discountIdx).name());
                Object freight = row.values().get(orders.columns().get(freightIdx).name());
                BigDecimal d = discount instanceof Number dn
                        ? BigDecimal.valueOf(dn.doubleValue()) : BigDecimal.ZERO;
                BigDecimal f = freight instanceof Number fn
                        ? BigDecimal.valueOf(fn.doubleValue()) : BigDecimal.ZERO;
                BigDecimal actual = total.subtract(d).add(f).setScale(2, RoundingMode.HALF_UP);
                setValue(row, orders.columns().get(actualAmountIdx).name(), actual);
            }
        }

        log.info("金额对账完成: {} 个订单汇总 {} 条商品项，total/actual 已回填",
                orders.rowCount(), orderItem.rowCount());
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
