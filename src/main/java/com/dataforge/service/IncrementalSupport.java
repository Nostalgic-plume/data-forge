package com.dataforge.service;

import com.dataforge.model.GeneratedData;

import java.util.Map;
import java.util.Objects;

/**
 * 增量生成支撑（R1，CLI 独有逻辑迁移）：修正作用域下界计算。
 */
public final class IncrementalSupport {

    private IncrementalSupport() {}

    /**
     * 增量修正作用域下界 = 各表现有 MAX(id)+1 的最小值（只修正新增区间，不触碰现有行）。
     *
     * @return 下界；无现有数据时返回 null（全量场景，修正不限作用域）
     */
    public static Long scopeMinIdOf(Map<String, GeneratedData> baseline) {
        long minStart = Long.MAX_VALUE;
        boolean any = false;
        for (GeneratedData data : baseline.values()) {
            long maxId = data.rows().stream()
                    .map(r -> r.values().get("id")).filter(Objects::nonNull)
                    .mapToLong(v -> ((Number) v).longValue()).max().orElse(0);
            if (data.rowCount() > 0) {
                any = true;
                minStart = Math.min(minStart, maxId + 1);
            }
        }
        return any ? minStart : null;
    }
}
