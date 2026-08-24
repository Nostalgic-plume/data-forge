package com.dataforge.expansion;

import com.dataforge.expansion.primitive.CountDistribution;
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
 * 订单商品项展开器（零 LLM）：orders × (1-5 项) + sku 快照回填。
 *
 * <ul>
 *   <li>每单 1-5 项（权重分布），总行数<b>精确达标</b>（补项给 1-2 项单、减项给 4-5 项单）</li>
 *   <li><b>下单快照</b>：order_no/product_name/sku_name/product_main_image/price 从父表取
 *       （DDL 注释"导入后统一回填"——展开器直接做对）</li>
 *   <li>subtotal = price × quantity（行内一致）</li>
 * </ul>
 */
public class OrderItemExpander implements Expander {

    private static final int[] QUANTITY_WEIGHTS = {60, 30, 10}; // 1/2/3 件

    @Override
    public String tableName() {
        return "order_item";
    }

    @Override
    public List<DataRow> expand(TableMeta table, PipelineContext context,
                                Map<String, Map<String, List<Object>>> completedData) {
        Map<String, List<Object>> orders = completedData.get("orders");
        Map<String, List<Object>> skus = completedData.get("sku");
        Map<String, List<Object>> products = completedData.get("product");
        if (orders == null || skus == null || products == null) {
            throw new IllegalStateException("order_item 展开失败：依赖表 orders/sku/product 未生成");
        }
        int orderCount = orders.get("id").size();
        int target = context.rowCount("order_item");
        Random rng = context.newRandom();
        int[] orderItemWeights = ExpansionConfig.defaultShop().orderItemWeights();

        // 每单商品项数：通用原语 CountDistribution（权重分布 + 精确校准到 target）
        int[] itemsPerOrder = CountDistribution.assign(orderCount, 1, 5, orderItemWeights, target, rng);

        List<DataRow> rows = new ArrayList<>(target);
        int skuCount = skus.get("id").size();
        for (int o = 0; o < orderCount; o++) {
            Object orderId = orders.get("id").get(o);
            Object orderNo = orders.get("order_no").get(o);
            Object orderCreateTime = orders.get("create_time").get(o);
            for (int k = 0; k < itemsPerOrder[o]; k++) {
                int skuIdx = rng.nextInt(skuCount);
                Object skuId = skus.get("id").get(skuIdx);
                Object skuName = skus.get("name").get(skuIdx);
                Object skuPrice = skus.get("price").get(skuIdx);
                Object productId = skus.get("product_id").get(skuIdx);
                Object productName = null, productImage = null;
                if (productId != null) {
                    int pIdx = indexOf(products.get("id"), productId);
                    if (pIdx >= 0) {
                        productName = products.get("name").get(pIdx);
                        productImage = products.get("main_image").get(pIdx);
                    }
                }
                int quantity = Zipf.weightedPick(toDouble(QUANTITY_WEIGHTS), rng) + 1;
                double price = skuPrice instanceof Number n ? n.doubleValue() : 0;

                Map<String, Object> row = new LinkedHashMap<>();
                for (ColumnDef col : table.columns()) {
                    row.put(col.name(), switch (col.name()) {
                        case "id" -> null; // PK 由骨架注入
                        case "order_id" -> orderId;
                        case "order_no" -> orderNo;
                        case "product_id" -> productId;
                        case "product_name" -> productName;
                        case "sku_id" -> skuId;
                        case "sku_name" -> skuName;
                        case "product_main_image" -> productImage;
                        case "price" -> price;
                        case "quantity" -> quantity;
                        case "subtotal" -> Math.round(price * quantity * 100.0) / 100.0;
                        case "create_time" -> orderCreateTime;
                        case "deleted" -> 0;
                        default -> null;
                    });
                }
                rows.add(tagAll(row, table));
            }
        }
        return rows;
    }

    private static double[] toDouble(int[] weights) {
        double[] d = new double[weights.length];
        for (int i = 0; i < weights.length; i++) d[i] = weights[i];
        return d;
    }

    private static int indexOf(List<Object> values, Object target) {
        if (target == null) return -1;
        String ts = target.toString();
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) != null && values.get(i).toString().equals(ts)) return i;
        }
        return -1;
    }
}
