package com.dataforge.expansion;

import com.dataforge.expansion.primitive.UniqueCombination;
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
 * 购物车展开器（零 LLM）：随机 user × sku 组合，满足唯一键 uk_user_sku（同用户同 SKU 仅一条）。
 * 冲突（已存在组合）则重抽 sku，直至目标行数。
 */
public class CartExpander implements Expander {

    @Override
    public String tableName() {
        return "cart";
    }

    @Override
    public List<DataRow> expand(TableMeta table, PipelineContext context,
                                Map<String, Map<String, List<Object>>> completedData) {
        Map<String, List<Object>> users = completedData.get("user");
        Map<String, List<Object>> skus = completedData.get("sku");
        if (users == null || skus == null) {
            throw new IllegalStateException("cart 展开失败：依赖表 user/sku 未生成");
        }
        List<Object> userIds = users.get("id");
        List<Object> skuIds = skus.get("id");
        List<Object> productIds = skus.get("product_id");
        int target = Math.min(context.rowCount("cart"), userIds.size() * skuIds.size());
        Random rng = context.newRandom();

        // 唯一组合采样（通用原语 UniqueCombination）：随机 user×sku 组合 + uk_user_sku 去重
        List<long[]> pairs = UniqueCombination.sample(
                userIds.size(), skuIds.size(), target, UniqueCombination.newSeen(), rng);

        List<DataRow> rows = new ArrayList<>(pairs.size());
        for (long[] pair : pairs) {
            Object userId = userIds.get((int) pair[0]);
            int skuIdx = (int) pair[1];
            Object skuId = skuIds.get(skuIdx);
            Object productId = productIds.get(skuIdx);

            Map<String, Object> row = new LinkedHashMap<>();
            String createTime = ValuePools.randomDateTime(90, rng);
            for (ColumnDef col : table.columns()) {
                row.put(col.name(), switch (col.name()) {
                    case "id" -> null; // PK 由骨架注入
                    case "user_id" -> userId;
                    case "product_id" -> productId;
                    case "sku_id" -> skuId;
                    case "quantity" -> 1 + rng.nextInt(3);
                    case "checked" -> rng.nextDouble() < 0.9 ? 1 : 0;
                    case "is_ordered" -> rng.nextDouble() < 0.1 ? 1 : 0;
                    case "create_time" -> createTime;
                    case "update_time" -> ValuePools.laterDateTime(createTime, rng);
                    case "deleted" -> rng.nextDouble() < 0.1 ? 1 : 0;
                    default -> null;
                });
            }
            rows.add(tagAll(row, table));
        }
        return rows;
    }
}
