package com.dataforge.expansion;

import com.dataforge.expansion.primitive.StatusTimeline;
import com.dataforge.model.ColumnDef;
import com.dataforge.model.DataRow;
import com.dataforge.model.TableMeta;
import com.dataforge.pipeline.PipelineContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 订单展开器（零 LLM）：user × 幂律 + 状态权重 + 状态-时间线联动。
 *
 * <ul>
 *   <li><b>user_id 幂律（Zipf 长尾）</b>：少数用户多订单，多数用户少订单（PLAN §4 真实感）</li>
 *   <li>status 权重（绝大多数 4 已完成）；pay_type 随状态联动</li>
 *   <li>时间线：create → pay → deliver → receive 按状态推进（状态-时间联动）</li>
 *   <li>total/actual 占位 0 → 跨表对账（Reconciliation）按 order_item 汇总回填</li>
 *   <li>order_no 规则唯一（DF + 14 位序号）</li>
 * </ul>
 */
public class OrdersExpander implements Expander {

    @Override
    public String tableName() {
        return "orders";
    }

    @Override
    public List<DataRow> expand(TableMeta table, PipelineContext context,
                                Map<String, Map<String, List<Object>>> completedData) {
        Map<String, List<Object>> users = completedData.get("user");
        Map<String, List<Object>> shops = completedData.get("shop");
        if (users == null || shops == null) {
            throw new IllegalStateException("orders 展开失败：依赖表 user/shop 未生成");
        }
        List<Object> userIds = users.get("id");
        List<Object> shopIds = shops.get("id");
        int target = context.rowCount("orders");
        Random rng = context.newRandom();
        double[] statusWeights = ExpansionConfig.defaultShop().statusWeights();
        // P5 增量：order_no 序号从现有最大之后继续（追加不冲突）
        int orderNoOffset = maxOrderNoSequence(completedData.get("orders"));

        List<DataRow> rows = new ArrayList<>(target);
        for (int i = 0; i < target; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            String createTime = ValuePools.randomDateTime(90, rng);
            int status = Zipf.weightedPick(statusWeights, rng);

            // 状态-时间线联动（通用原语 StatusTimeline：状态机驱动事件字段，时间按序推进）
            StatusTimeline.Timeline tl = StatusTimeline.build(status, createTime, List.of(
                    StatusTimeline.Step.when(s -> s >= 1 && s != 5, "pay_time",
                            (t, r) -> ValuePools.laterDateTime(t, r)),
                    StatusTimeline.Step.when(s -> s >= 2, "deliver_time",
                            (t, r) -> ValuePools.laterDateTime(t, r)),
                    StatusTimeline.Step.when(s -> s >= 3, "receive_time",
                            (t, r) -> ValuePools.laterDateTime(t, r))
            ), rng);
            String payTime = (String) tl.fields().get("pay_time");
            String deliverTime = (String) tl.fields().get("deliver_time");
            String receiveTime = (String) tl.fields().get("receive_time");
            Integer payType = payTime != null ? 1 + rng.nextInt(2) : null; // 1微信 2支付宝
            String lastEvent = tl.lastEvent();

            // 金额规则：discount 60% 无优惠否则 5~50 圆整；freight 70% 包邮否则 10/12/15
            double discount = rng.nextDouble() < 0.6 ? 0 : Math.round((5 + rng.nextDouble() * 45) * 100.0) / 100.0;
            double freight = rng.nextDouble() < 0.7 ? 0 : new double[]{10, 12, 15}[rng.nextInt(3)];

            for (ColumnDef col : table.columns()) {
                row.put(col.name(), switch (col.name()) {
                    case "id" -> null; // PK 由骨架注入
                    case "order_no" -> "DF" + String.format("%014d", orderNoOffset + i + 1);
                    case "user_id" -> userIds.get(Zipf.zipfPick(userIds.size(), rng));
                    case "shop_id" -> shopIds.get(rng.nextInt(shopIds.size()));
                    case "total_amount", "actual_amount" -> 0.0; // 对账回填
                    case "discount_amount" -> discount;
                    case "freight" -> freight;
                    case "status" -> status;
                    case "receiver_name" -> ValuePools.randomChineseName(rng);
                    case "receiver_phone" -> ValuePools.randomPhone(rng);
                    case "receiver_address" -> ValuePools.randomAddress(rng);
                    case "pay_type" -> payType;
                    case "pay_time" -> payTime;
                    case "deliver_time" -> deliverTime;
                    case "logistics_no" -> status >= 2 ? ValuePools.randomLogisticsNo(rng) : "";
                    case "receive_time" -> receiveTime;
                    case "remark" -> ValuePools.randomRemark(rng);
                    case "create_time" -> createTime;
                    case "update_time" -> ValuePools.laterDateTime(lastEvent, rng);
                    case "deleted" -> rng.nextDouble() < 0.1 ? 1 : 0;
                    default -> null;
                });
            }
            rows.add(tagAll(row, table));
        }
        return rows;
    }

    /** P5 增量：解析现有 order_no（DF+14 位序号）的最大值，新增订单从其后继续（追加不冲突） */
    private static int maxOrderNoSequence(Map<String, List<Object>> existingOrders) {
        if (existingOrders == null) return 0;
        List<Object> orderNos = existingOrders.get("order_no");
        if (orderNos == null) return 0;
        int max = 0;
        for (Object no : orderNos) {
            if (no instanceof String s && s.startsWith("DF")) {
                try {
                    max = Math.max(max, (int) Long.parseLong(s.substring(2)));
                } catch (NumberFormatException ignored) {
                    // 非标准 order_no（如空串）忽略
                }
            }
        }
        return max;
    }
}
