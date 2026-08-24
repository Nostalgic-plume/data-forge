package com.dataforge.llm;

import com.dataforge.model.ColumnDef;
import com.dataforge.model.ProjectRules;

import java.util.List;

/**
 * LLM Prompt 构建器（移植 real-world-data ContentPromptBuilder 的列定义/质量约束部分）。
 *
 * <p>统一要求 LLM 输出严格 JSON：<pre>{"rows": [{"col": "value", ...}]}</pre>
 * 与 {@link RealLlmClient.JsonRows} 结构化输出契约一致。</p>
 */
public final class PromptBuilder {

    private PromptBuilder() {}

    /** 系统提示：列定义 + 业务语义（COMMENT）+ 质量约束 */
    public static String buildSystemPrompt(String tableName, List<ColumnDef> llmCols, int rowCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个数据生成助手，为表 `").append(tableName).append("` 生成模拟数据。\n\n");
        sb.append("## 列定义（COMMENT 是业务语义的唯一依据，严格按注释生成合理值）\n");
        for (ColumnDef col : llmCols) {
            sb.append("- ").append(col.name()).append(" (").append(col.type()).append(")");
            if (col.comment() != null && !col.comment().isEmpty()) {
                sb.append(" — ").append(col.comment());
            }
            if (col.nullable()) sb.append(" [可空]");
            sb.append("\n");
        }

        sb.append("\n## 要求\n");
        sb.append("1. 所有文本字段使用中文输出\n");
        sb.append("2. 数据必须真实、合理，符合列定义和业务语义\n");
        sb.append("3. 字符串列不要生成长篇重复文本；描述/备注不超过 200 字\n");
        sb.append("4. 数字列生成合理范围内的值\n");
        sb.append("5. 必须为每一行提供所有列的值（可空列可以缺省）\n");
        sb.append("6. 生成 ").append(llmCols.size()).append(" 列 × ").append(rowCount).append(" 行\n");

        sb.append("\n## 输出格式（硬性要求）\n");
        sb.append("只输出严格 JSON，格式：{\"rows\": [{\"列名\": \"值\", ...}, ...]}\n");
        sb.append("不要输出 markdown 代码块、注释、解释文字或多余内容。\n");

        sb.append("\n## 数据质量约束\n");
        sb.append("1. 手机号：纯数字 11 位，不含空格、连字符或括号\n");
        sb.append("2. 邮箱：常见域名（qq.com、163.com、gmail.com），不含空格\n");
        sb.append("3. 地址：20-100 字符，包含省市区三级\n");
        sb.append("4. 折扣字段（discount/discount_rate）：0.0~1.0，如 0.85 表示八五折\n");
        sb.append("5. 评分（score/rating/star）：1~10，整数或一位小数\n");
        sb.append("6. 价格字段：original_price ≥ price，且 original_price / price ≤ 5\n");
        sb.append("7. 时间顺序：create_time 必须早于 update_time；pay_time 晚于下单时间\n");
        sb.append("8. 收货人/昵称等姓名：中文名\n");
        return sb.toString();
    }

    /** 用户提示：列清单 + 行数 + FK 父表上下文 */
    public static String buildUserPrompt(String tableName, List<ColumnDef> llmCols,
                                         int rowCount, String fkContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为表 `").append(tableName).append("` 生成 ").append(rowCount).append(" 条数据。\n\n");
        sb.append("需要生成的列：\n");
        for (ColumnDef col : llmCols) {
            sb.append("- ").append(col.name()).append(" (").append(col.type()).append(")");
            if (col.comment() != null && !col.comment().isEmpty()) {
                sb.append(" — ").append(col.comment());
            }
            sb.append("\n");
        }
        if (fkContext != null && !fkContext.isBlank()) {
            sb.append("\n").append(fkContext);
        }
        sb.append("\n请按格式输出 JSON。");
        return sb.toString();
    }

    /**
     * 前置约束节文本（决策 #21：project_rules 是唯一参数源）：
     * 把该表相关的 generationRules 格式化成 prompt 段落，注入生成时 LLM 必须遵守。
     * 无该表规则返回空串。
     *
     * @param tableName        目标表
     * @param generationRules  前置约束规则（currentRules().generationRules()）
     * @return 约束段落（含标题）；无相关规则返回空串
     */
    public static String buildConstraintsSection(String tableName, List<ProjectRules.Rule> generationRules) {
        if (generationRules == null || generationRules.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ProjectRules.Rule r : generationRules) {
            if (r.table() != null && r.table().equalsIgnoreCase(tableName)) {
                sb.append("- ").append(r.expression() != null ? r.expression() : r.type()).append("\n");
            }
        }
        if (sb.length() == 0) return "";
        return "\n## 业务约束（分析 agent 提炼，生成时必须遵守）\n" + sb;
    }

    /**
     * 构建父表上下文文本（移植 ContentPromptBuilder.buildParentContext）：
     * FK 值 → 父表业务记录，供 LLM 生成业务语义匹配的子表数据。
     */
    public static String buildParentContext(String tableName,
                                            List<ColumnDef> columns,
                                            java.util.List<com.dataforge.model.ForeignKeyDef> fks,
                                            java.util.Map<String, java.util.Map<String, java.util.List<Object>>> completedData) {
        if (completedData == null || completedData.isEmpty()) return null;

        StringBuilder ctx = new StringBuilder("已生成的父表数据（生成子表时请参考对应的父表记录，使业务语义匹配）：\n");
        for (com.dataforge.model.ForeignKeyDef fk : fks) {
            java.util.Map<String, java.util.List<Object>> parentData = completedData.get(fk.refTable());
            if (parentData == null) continue;

            java.util.List<Object> pkValues = parentData.get(fk.refColumn());
            if (pkValues == null || pkValues.isEmpty()) continue;

            java.util.List<String> dataCols = parentData.keySet().stream()
                    .filter(k -> !k.equals(fk.refColumn()))
                    .toList();

            ctx.append("\n列 `").append(fk.column()).append("` → 引用 `")
                    .append(fk.refTable()).append("`：\n");

            int showCount = Math.min(pkValues.size(), 20);
            for (int i = 0; i < showCount; i++) {
                ctx.append("  [").append(pkValues.get(i)).append("]");
                for (String dataCol : dataCols) {
                    java.util.List<Object> vals = parentData.get(dataCol);
                    if (vals != null && i < vals.size() && vals.get(i) != null) {
                        String val = vals.get(i).toString();
                        ctx.append(", ").append(dataCol).append("=")
                                .append(val.length() > 60 ? val.substring(0, 60) + "..." : val);
                    }
                }
                ctx.append("\n");
            }
            if (pkValues.size() > 20) {
                ctx.append("  ... 还有 ").append(pkValues.size() - 20).append(" 条记录未展示\n");
            }
        }
        return ctx.length() > 0 ? ctx.toString() : null;
    }
}
