package com.dataforge.model;

import java.util.Map;

/**
 * 一行数据（API_CONTRACT §2.2）。
 *
 * @param values  列名 → 值
 * @param lineage 每列来源标注（血缘，调试/面试）
 */
public record DataRow(
        Map<String, Object> values,
        Map<String, SourceTag> lineage
) {
}
