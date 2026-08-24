package com.dataforge.model;

import java.util.List;
import java.util.Map;

/**
 * 增量配置（API_CONTRACT §2.6）。全量是增量的特例（mode=full）。
 * P0 仅支持全量模式，类型先落地（P5 实现增量语义）。
 *
 * @param mode     "full" | "incremental"
 * @param baseline 各表现有 MAX(id)（必须从目标库读，P5 实现）
 * @param scope    本次新增哪些表
 * @param newRows  新增表的目标行数
 */
public record IncrementalConfig(
        String mode,
        Map<String, Long> baseline,
        List<String> scope,
        Map<String, Integer> newRows
) {
    public static IncrementalConfig full() {
        return new IncrementalConfig("full", Map.of(), List.of(), Map.of());
    }

    public boolean isIncremental() {
        return "incremental".equals(mode);
    }
}
