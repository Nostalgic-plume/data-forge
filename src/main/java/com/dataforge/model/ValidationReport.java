package com.dataforge.model;

import java.util.List;
import java.util.Map;

/**
 * 校验报告（API_CONTRACT §2.4）：Detect 全量扫描 → Aggregate 分桶压缩。
 * 喂给修正 agent（P3）的就是分桶结果，不看全量。
 *
 * @param buckets      问题分桶（给 LLM/人工看的压缩视图）
 * @param totalErrors  问题总行数
 * @param elapsedMs    校验耗时
 */
public record ValidationReport(
        List<Bucket> buckets,
        int totalErrors,
        long elapsedMs
) {
    /** 问题分桶：错误类型 → 数量/占比/样本/根因假设 */
    public record Bucket(
            String table,
            String column,
            String errorType,          // FK_ORPHAN / UNIQUE_CONFLICT / AMOUNT_MISMATCH / NULL ...
            int count,
            double ratio,
            List<Map<String, Object>> samples,  // 前 3 条整行快照（含 id，供 id 锚定决策）
            String rootCause,                   // 规则引擎第一层根因推断
            String rootCauseKey                 // 规范化根因（同根因合并）：FK_PARENT_FAIL:{父表} / UNIQUE_DUP / AMOUNT_MISMATCH / {type}
    ) {
    }
}
