package com.dataforge.validation;

import com.dataforge.model.DataRow;
import com.dataforge.model.ForeignKeyDef;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.TableMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 确定性校验器（规则引擎，非 LLM——信任模型红线）。
 *
 * <p>三类校验（内存全量扫描，CLI 汇总输出）：
 * <ul>
 *   <li><b>FK 悬挂</b>：子表 FK 引用值必须 ⊆ 父表 refColumn 值集合（父表在生成集内且未失败才查）；
 *       FK 值缺失（null）同样计悬挂</li>
 *   <li><b>唯一键冲突</b>：uniqueKeys 列组的连接值必须唯一</li>
 *   <li><b>金额对账</b>：orders.total_amount == Σ(order_item.subtotal by order_id)
 *       （±0.01 容差；order_item 未生成则不核对）</li>
 * </ul>
 * 后续 P2 演进：Detect 全量 SQL → Aggregate 分桶压缩（API_CONTRACT §3）。</p>
 */
public final class DataValidator {

    private static final Logger log = LoggerFactory.getLogger(DataValidator.class);

    public record ValidationSummary(
            int fkOrphanCount,          // FK 悬挂行数（含缺失）
            int uniqueConflictCount,    // 唯一键冲突行数
            int amountMismatchCount,    // 金额对账差异订单数
            boolean amountChecked,      // 金额对账是否实际执行（orders↔order_item 同时生成）
            List<String> notes          // 提示（跳过原因等）
    ) {
        public boolean clean() {
            return fkOrphanCount == 0 && uniqueConflictCount == 0
                    && (!amountChecked || amountMismatchCount == 0);
        }
    }

    private DataValidator() {}

    public static ValidationSummary validate(List<TableMeta> tables, Map<String, GeneratedData> data,
                                             Set<String> failedTables) {
        List<String> notes = new ArrayList<>();
        int fkOrphan = 0;
        int uniqueConflict = 0;

        Map<String, TableMeta> tableMap = new HashMap<>();
        for (TableMeta t : tables) tableMap.put(t.name(), t);

        // 父表引用值索引：refTable.refColumn → 值集合（仅生成集内且未失败的表）
        Map<String, Set<String>> refIndex = new HashMap<>();
        for (String tableName : data.keySet()) {
            if (failedTables.contains(tableName)) continue;
            TableMeta meta = tableMap.get(tableName);
            if (meta == null) continue;
            for (var col : meta.columns()) {
                Set<String> values = new HashSet<>();
                for (DataRow row : data.get(tableName).rows()) {
                    Object v = row.values().get(col.name());
                    if (v != null) values.add(v.toString());
                }
                refIndex.put(tableName + "." + col.name(), values);
            }
        }

        // 1. FK 悬挂
        for (String tableName : data.keySet()) {
            if (failedTables.contains(tableName)) continue;
            TableMeta meta = tableMap.get(tableName);
            if (meta == null) continue;
            for (ForeignKeyDef fk : meta.foreignKeys()) {
                Set<String> parentValues = refIndex.get(fk.refTable() + "." + fk.refColumn());
                if (parentValues == null) {
                    // 父表未生成/失败 → 该 FK 的悬挂无法核对，记录提示
                    notes.add("FK 悬挂跳过: `" + tableName + "`.`" + fk.column() + "` 父表 `"
                            + fk.refTable() + "` 未生成或失败");
                    continue;
                }
                for (DataRow row : data.get(tableName).rows()) {
                    Object v = row.values().get(fk.column());
                    if (v == null || !parentValues.contains(v.toString())) {
                        fkOrphan++;
                    }
                }
            }
        }

        // 2. 唯一键冲突
        for (String tableName : data.keySet()) {
            if (failedTables.contains(tableName)) continue;
            TableMeta meta = tableMap.get(tableName);
            if (meta == null) continue;
            for (String ukGroup : meta.uniqueKeys()) {
                List<String> cols = Arrays.stream(ukGroup.split(",")).map(String::trim).toList();
                Set<String> seen = new HashSet<>();
                for (DataRow row : data.get(tableName).rows()) {
                    StringBuilder key = new StringBuilder();
                    boolean allNull = true;
                    for (String col : cols) {
                        Object v = row.values().get(col);
                        if (v != null) {
                            key.append(col).append("=").append(v).append("|");
                            allNull = false;
                        } else {
                            key.append(col).append("=null|");
                        }
                    }
                    if (allNull) continue; // MySQL 唯一索引允许全 NULL 重复
                    if (!seen.add(key.toString())) uniqueConflict++;
                }
            }
        }

        // 3. 金额对账
        int amountMismatch = 0;
        boolean amountChecked = false;
        GeneratedData orders = data.get("orders");
        GeneratedData orderItem = data.get("order_item");
        if (orders != null && orderItem != null
                && !failedTables.contains("orders") && !failedTables.contains("order_item")) {
            amountChecked = true;
            int orderIdIdx = colIndex(orderItem, "order_id");
            int subtotalIdx = colIndex(orderItem, "subtotal");
            int idIdx = colIndex(orders, "id");
            int totalIdx = colIndex(orders, "total_amount");
            if (orderIdIdx < 0 || subtotalIdx < 0 || idIdx < 0 || totalIdx < 0) {
                notes.add("金额对账跳过: 缺少必要列（order_id/subtotal/id/total_amount）");
                amountChecked = false;
            } else {
                Map<String, BigDecimal> orderTotals = new HashMap<>();
                for (DataRow row : orderItem.rows()) {
                    Object orderId = row.values().get(orderItem.columns().get(orderIdIdx).name());
                    Object subtotal = row.values().get(orderItem.columns().get(subtotalIdx).name());
                    if (orderId == null) continue;
                    BigDecimal add = subtotal instanceof Number s
                            ? BigDecimal.valueOf(s.doubleValue()) : BigDecimal.ZERO;
                    orderTotals.merge(orderId.toString(), add, BigDecimal::add);
                }
                String idCol = orders.columns().get(idIdx).name();
                String totalCol = orders.columns().get(totalIdx).name();
                for (DataRow row : orders.rows()) {
                    Object orderId = row.values().get(idCol);
                    Object total = row.values().get(totalCol);
                    if (orderId == null || total == null) {
                        amountMismatch++;
                        continue;
                    }
                    BigDecimal expected = orderTotals.getOrDefault(orderId.toString(), BigDecimal.ZERO);
                    BigDecimal actual = total instanceof Number n
                            ? BigDecimal.valueOf(n.doubleValue()).setScale(2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    if (expected.subtract(actual).abs().compareTo(new BigDecimal("0.01")) > 0) {
                        amountMismatch++;
                    }
                }
            }
        }

        log.info("校验完成: FK 悬挂={}, 唯一冲突={}, 金额差异={}（{}）",
                fkOrphan, uniqueConflict, amountMismatch, amountChecked ? "已核对" : "未核对");
        return new ValidationSummary(fkOrphan, uniqueConflict, amountMismatch, amountChecked, notes);
    }

    private static int colIndex(GeneratedData data, String column) {
        for (int i = 0; i < data.columns().size(); i++) {
            if (data.columns().get(i).name().equals(column)) return i;
        }
        return -1;
    }
}
