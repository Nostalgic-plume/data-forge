package com.dataforge.model;

/**
 * 数据血缘标注：每列值的来源（API_CONTRACT §2.2）。
 * P0 记录：规则列 RULE / 外键 FK / 主键 PK / LLM 语义列 LLM / 规则展开 SEED。
 */
public enum SourceTag {
    RULE,       // 确定性规则生成（格式/枚举/日期）
    FK,         // 外键预分配注入
    PK,         // 主键骨架注入
    LLM,        // LLM 语义列生成
    EXPAND,     // 展开器派生（P1）
    CORRECTED,  // 修正 agent 修复（P3）
    SEED        // 确定性种子派生
}
