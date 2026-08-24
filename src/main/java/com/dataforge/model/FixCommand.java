package com.dataforge.model;

import java.util.Map;

/**
 * 修正指令（API_CONTRACT §2.5）：id 锚定 + 列级最小干预。
 * 修正 agent（P3）应用阶段的产物，也是审计/面试叙事的最小单位。
 *
 * @param table  目标表
 * @param id     目标行主键（精确落位，不做整行重写）
 * @param fixes  列级修改（最小干预，只含问题列）
 * @param reason 修正理由（审计/面试）
 */
public record FixCommand(
        String table,
        long id,
        Map<String, Object> fixes,
        String reason
) {
}
