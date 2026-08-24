package com.dataforge.model;

import java.util.Map;

/**
 * 列工具决策（工具感知列分类核心）：分析 agent 对某列选择的生成方式。
 *
 * <p>tool 取 {@code com.dataforge.expansion.ToolRegistry} 中的工具名（fk/seq/weighted/enum/
 * date/faker/valueRange/const...）或语义型 {@code llm}。params 为工具参数（如 weights/range/type）。
 *
 * <p>信任模型红线：LLM 只产出此<b>决策</b>，工具执行仍由确定性代码完成。</p>
 */
public record ColumnToolDecision(
        String column,
        String tool,
        Map<String, String> params,
        double confidence
) {
    public static ColumnToolDecision of(String column, String tool,
                                        Map<String, String> params, double confidence) {
        return new ColumnToolDecision(column, tool,
                params == null ? Map.of() : params, confidence);
    }

    public String param(String key) {
        return params().get(key);
    }
}
