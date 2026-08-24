package com.dataforge.pipeline;

import com.dataforge.model.DataRow;
import com.dataforge.model.TableMeta;

import java.util.*;

/**
 * 唯一键确定性去重（P1 最小干预修正）：
 * LLM 样本表生成后，对 uniqueKeys 列组去重——重复值追加序号（zhangsan → zhangsan2）。
 *
 * <p>替代"整表重生成"（P0 的重试放大元凶）：确定性代码保证唯一性，
 * 避免 LLM 反复生成仍重复导致的昂贵重试。符合"LLM 决策、代码执行"边界。</p>
 */
public final class UniqueDeduper {

    private UniqueDeduper() {}

    /** 去重：仅处理单列唯一键组（LLM 样本表的常见形态：username/order_no/code） */
    public static void dedupe(List<DataRow> rows, TableMeta table) {
        if (rows == null || rows.isEmpty()) return;
        for (String ukGroup : table.uniqueKeys()) {
            List<String> cols = Arrays.stream(ukGroup.split(",")).map(String::trim).toList();
            if (cols.size() != 1) continue; // 复合唯一键（cart.uk_user_sku 等）由展开器保证
            String col = cols.get(0);
            Set<String> seen = new HashSet<>();
            for (DataRow row : rows) {
                Object v = row.values().get(col);
                if (v == null) continue;
                String key = v.toString();
                if (seen.add(key)) continue;
                // 冲突：追加序号（zhangsan → zhangsan2 → zhangsan3 ...）
                int n = 2;
                String candidate = key + n;
                while (!seen.add(candidate)) {
                    candidate = key + (++n);
                }
                row.values().put(col, candidate);
            }
        }
    }
}
