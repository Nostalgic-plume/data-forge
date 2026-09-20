package com.dataforge.expansion.blueprint;

import com.dataforge.expansion.Expander;
import com.dataforge.model.DataRow;
import com.dataforge.model.TableMeta;
import com.dataforge.model.PipelineContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用派生展开器（库无关引擎）：执行 {@link DerivedTableConfig} 声明的行构建规则。
 *
 * <p>流程：检查父表 → 计算派生行数（每父行 × 变体数，截断到目标）→ 每行按列规则求值
 * （含血缘 EXPAND + 锚点预计算 + 全局序号）。换库 = 新写配置，无需改此引擎。</p>
 *
 * <p>特殊逻辑（状态机/精确达标/组合去重）不适合声明式，保留 Java 派生器
 * （orders/order_item/cart）。</p>
 */
public final class DerivedTableExpander implements Expander {

    private final DerivedTableConfig config;
    private final AtomicLong seq = new AtomicLong(1);

    public DerivedTableExpander(DerivedTableConfig config) {
        this.config = config;
    }

    @Override
    public String tableName() {
        return config.table;
    }

    @Override
    public List<DataRow> expand(TableMeta table, PipelineContext context,
                                Map<String, Map<String, List<Object>>> completedData) {
        Map<String, List<Object>> parentColumns = completedData.get(config.parentTable);
        if (parentColumns == null || parentColumns.isEmpty()) {
            throw new IllegalStateException("表 `" + config.table + "` 展开失败：依赖表 `"
                    + config.parentTable + "` 未生成");
        }
        int parentCount = parentColumns.values().iterator().next().size();
        int maxParent = Math.min(parentCount, config.maxParents);
        int perParent = config.rowsPerParent;
        int total = Math.min(perParent * maxParent, context.rowCount(config.table));

        Random rng = context.newRandom();
        // 锚点预计算（每父行一个确定性基础值，跨变体共享）
        double[] anchors = new double[maxParent];
        if (config.anchorMax > config.anchorMin) {
            for (int i = 0; i < maxParent; i++) {
                anchors[i] = config.anchorMin + rng.nextDouble() * (config.anchorMax - config.anchorMin);
            }
        }

        List<DataRow> rows = new ArrayList<>(total);
        outer:
        for (int p = 0; p < maxParent; p++) {
            for (int v = 0; v < perParent; v++) {
                if (rows.size() >= total) break outer;
                Map<String, Object> row = new LinkedHashMap<>();
                RowCtx ctx = new RowCtx(p, v, rng, completedData, parentColumns, anchors, seq, row,
                        config.variants, config.variantFactors);
                for (Map.Entry<String, DerivedTableConfig.ColumnRule> e : config.columns.entrySet()) {
                    Object value = e.getValue().apply(ctx);
                    if (value != null) {
                        row.put(e.getKey(), value);
                    }
                }
                rows.add(tagAll(row, table));
            }
        }
        return rows;
    }
}
