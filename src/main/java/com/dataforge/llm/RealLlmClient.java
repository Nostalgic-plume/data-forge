package com.dataforge.llm;

import com.dataforge.model.ColumnDef;
import com.dataforge.model.ColumnToolDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

/**
 * 真实 LLM 客户端：Spring AI ChatClient 结构化输出（OpenAI 兼容端点接 DeepSeek）。
 *
 * <p>主路径：<pre>.call().entity(JsonRows.class)</pre>（Spring AI JSON schema 约束）；
 * 容错路径：entity 解析失败 → 重新调用取原始文本，剥离 markdown 代码块后手动解析
 * （模型偶发输出不合 schema 时的兜底，代价为一次额外调用——由上层补差/降批兜底成本）。</p>
 *
 * <p>凭证：.env（LLM_API_KEY / LLM_BASE_URL / LLM_MODEL），未配置时抛中文错误提示。</p>
 */
public class RealLlmClient implements DataLlm {

    private static final Logger log = LoggerFactory.getLogger(RealLlmClient.class);

    /** 结构化输出契约：{"rows": [{"col": "value", ...}, ...]} */
    public record JsonRows(List<Map<String, Object>> rows) {
    }

    /** 修正输出契约：{"fixes": [{"id": 1, "<列名>": 修正值}, ...]} */
    public record CorrectRows(List<Map<String, Object>> fixes) {
    }

    /** 规则提炼输出契约：{"rules": [{"table","column","type","expression","confidence","params"}]} */
    public record ExtractedRules(List<ExtractedRule> rules) {
    }

    /** params 值可能为嵌套对象（如 weights={"0":0.10,...}）——用 Object 承接 */
    public record ExtractedRule(String table, String column, String type, String expression,
                                double confidence, Map<String, Object> params) {
    }

    /** 把 params 的 Object 值转成字符串：嵌套 Map（weights）→ "0:0.10,1:0.20" */
    private static Map<String, String> paramsToString(Map<String, Object> raw) {
        if (raw == null) return Map.of();
        Map<String, String> out = new java.util.LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v instanceof Map<?, ?> m) {
                out.put(k, m.entrySet().stream()
                        .map(e -> e.getKey() + ":" + e.getValue())
                        .collect(java.util.stream.Collectors.joining(",")));
            } else if (v != null) {
                out.put(k, String.valueOf(v));
            }
        });
        return out;
    }

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RealLlmClient(ChatClient chatClient) {
        this.chatClient = chatClient;
        verifyApiKeyConfigured();
    }

    private void verifyApiKeyConfigured() {
        String apiKey = System.getProperty("LLM_API_KEY", System.getenv("LLM_API_KEY"));
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("sk-xxx")) {
            throw new IllegalStateException(
                    "LLM_API_KEY 未配置：请在 dataforge/.env 填写（LLM_API_KEY/LLM_BASE_URL/LLM_MODEL），"
                            + "或用 --mock-llm 干跑模式验证管线（无需真实 key）");
        }
    }

    @Override
    public List<Map<String, Object>> generateRows(String tableName, List<ColumnDef> llmCols,
                                                  int rowCount, String fkContext) {
        String system = PromptBuilder.buildSystemPrompt(tableName, llmCols, rowCount);
        String user = PromptBuilder.buildUserPrompt(tableName, llmCols, rowCount, fkContext);

        try {
            // 主路径：结构化输出
            JsonRows jsonRows = chatClient.prompt()
                    .system(system)
                    .user(user)
                    .call()
                    .entity(JsonRows.class);
            if (jsonRows != null && jsonRows.rows() != null && !jsonRows.rows().isEmpty()) {
                return jsonRows.rows();
            }
            log.warn("表 `{}` entity 返回空 rows，走手动解析兜底", tableName);
        } catch (Exception e) {
            log.warn("表 `{}` 结构化输出失败（{}），走手动解析兜底", tableName, e.getMessage());
        }

        // 容错路径：重新调用取原始文本 → 剥离代码块 → 手动解析
        String content = chatClient.prompt()
                .system(system)
                .user(user)
                .call()
                .content();
        String cleaned = stripCodeFence(content);
        try {
            JsonRows parsed = objectMapper.readValue(cleaned, JsonRows.class);
            if (parsed != null && parsed.rows() != null && !parsed.rows().isEmpty()) {
                return parsed.rows();
            }
        } catch (Exception e) {
            log.error("表 `{}` 手动解析 JSON 失败：{}", tableName, e.getMessage());
        }
        throw new IllegalStateException("表 `" + tableName + "` LLM 输出无法解析为 rows JSON");
    }

    /** 剥离 markdown 代码块 ```json ... ``` 包裹 */
    static String stripCodeFence(String content) {
        if (content == null) return "{}";
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    @Override
    public List<Map<String, Object>> correctValues(String tableName, List<ColumnDef> columns,
                                                   List<Map<String, Object>> samples) {
        if (columns.isEmpty() || samples.isEmpty()) return List.of();
        ColumnDef col = columns.get(0);
        String system = "你是数据修正助手。表 `" + tableName + "` 的列 `" + col.name() + "` ("
                + col.type() + ") 存在数据问题，需要你依据样本行给出修正后的值。\n"
                + (col.comment() != null && !col.comment().isEmpty() ? "列语义：" + col.comment() + "\n" : "")
                + "只输出严格 JSON：{\"fixes\": [{\"id\": <原行id>, \"" + col.name() + "\": \"修正值\"}, ...]}。\n"
                + "不要输出 markdown 代码块或解释文字。每行 id 必须与输入样本一致。";

        StringBuilder user = new StringBuilder("请修正以下行的 `").append(col.name()).append("`：\n");
        for (Map<String, Object> s : samples) {
            user.append(s).append("\n");
        }
        user.append("请按格式输出 JSON。");

        try {
            CorrectRows out = chatClient.prompt().system(system).user(user.toString())
                    .call().entity(CorrectRows.class);
            if (out != null && out.fixes() != null && !out.fixes().isEmpty()) {
                return out.fixes();
            }
            log.warn("表 `{}` 修正 entity 返回空 fixes，放弃该桶修正", tableName);
        } catch (Exception e) {
            log.warn("表 `{}` 修正结构化输出失败（{}），放弃该桶修正", tableName, e.getMessage());
        }
        return List.of();
    }

    /** 行数推荐输出契约：{"recommendations":[{"table","rows"}]} */
    public record RowRecommendations(List<RowRec> recommendations) {
    }

    public record RowRec(String table, int rows) {
    }

    /** 解析阶段行数推荐：LLM 按关联推导每表合理行数（不提炼规则） */
    @Override
    public Map<String, Integer> recommendRows(String schemaSummary) {
        String system = "你是数据库行数推荐专家。根据表结构（表/列/COMMENT/FK）推荐每表合理的数据行数，"
                + "按关联推导（如 user 10 → order_item 约 25、address ≈ user×2）。"
                + "只输出严格 JSON {\"recommendations\":[{\"table\":\"...\",\"rows\":10}]}。";
        try {
            RowRecommendations out = chatClient.prompt().system(system)
                    .user(schemaSummary).call().entity(RowRecommendations.class);
            if (out != null && out.recommendations() != null) {
                return out.recommendations().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                RowRec::table, RowRec::rows, (a, b) -> a, java.util.LinkedHashMap::new));
            }
            log.warn("行数推荐返回空，回退基础默认");
        } catch (Exception e) {
            log.warn("行数推荐失败（{}），回退基础默认", e.getMessage());
        }
        return Map.of();
    }

    /** P6 规则修订（HITL）：用户自然语言指令 → LLM 修订规则列表（结构化输出） */
    @Override
    public List<com.dataforge.model.ProjectRules.Rule> refineRules(
            List<com.dataforge.model.ProjectRules.Rule> current, String instruction) {
        String system = "你是数据库数据生成规则修订助手。根据用户指令修订规则列表。\n"
                + "规则类型（type）：AGG_EQUAL（金额对账，params 含 ref_table/sum_col/join_col）/"
                + "ENUM_WEIGHT（枚举权重，params 含 weights）/ STATUS_FLOW（状态联动，params 含 flow）"
                + "/ VALUE_RANGE（范围，params 含 min/max）/ USER_NOTE（用户备注）"
                + "/ CUSTOM_SQL（自定义校验，params 含 check_sql 与可选 fix_sql）。\n"
                + "CUSTOM_SQL 用法：当规则无法用固定类型表达时使用（如\"已发货订单必须有物流单号\"），confidence 标 <0.7。"
                + "check_sql 为只读 SELECT，表名必须用 {schema} 占位符（如 {schema}.orders），"
                + "结果必须含目标表 id 列（如 SELECT t.id AS id）；fix_sql（可选）为修复 SQL："
                + "UPDATE {schema}.表名 SET 列 = 值 WHERE id = {id}，表名必须等于规则表，"
                + "{id} 运行时绑定每条问题行 id；禁止 DELETE/DROP/INSERT、禁止无 WHERE 的全表 UPDATE。\n"
                + "保留现有规则除非用户明确要求删除/修改。输出只 JSON {\"rules\":[{\"table\":\"...\","
                + "\"column\":\"...\",\"type\":\"...\",\"expression\":\"...\",\"confidence\":0.9,"
                + "\"params\":{\"key\":\"value\"}}]}。";
        String user = "当前规则：\n" + current + "\n\n用户指令：\n" + instruction;
        try {
            ExtractedRules out = chatClient.prompt().system(system).user(user)
                    .call().entity(ExtractedRules.class);
            if (out != null && out.rules() != null) {
                return out.rules().stream()
                        .map(r -> new com.dataforge.model.ProjectRules.Rule(
                                r.table(), r.column(), r.type(), r.expression(), r.confidence(),
                                com.dataforge.model.ProjectRules.Rule.Source.USER_CONFIRMED,
                                paramsToString(r.params())))
                        .toList();
            }
        } catch (Exception e) {
            log.warn("规则修订失败（{}），保留当前规则", e.getMessage());
        }
        return current;
    }

    /** P6 规则提炼：结构化输出（表结构 → 业务规则，含置信度），失败返回空（分析兜底内置默认） */
    @Override
    public List<com.dataforge.model.ProjectRules.Rule> extractRules(String schemaName, String schemaSummary) {
        String system = "你是数据库业务规则分析专家。根据表结构（表/列/COMMENT/枚举/FK/唯一键）"
                + "提炼数据生成约束规则，只输出严格 JSON。\n"
                + "规则类型（type）：\n"
                + "- AGG_EQUAL：目标列 = 引用表某列按 join 键求和（如订单总额 = Σ 明细小计），"
                + "  params 需含 ref_table / sum_col / join_col\n"
                + "- ENUM_WEIGHT：枚举列取值权重（如状态绝大多数为某值），params 含 weights（值:权重）\n"
                + "- STATUS_FLOW：状态与时间字段联动，params 含 flow（逗号分隔时间字段，需 ≥2 个）"
                + "  + trigger_status（可选，触发状态值）+ trigger_status_col（可选，触发列，默认 status）\n"
                + "- VALUE_RANGE：列取值范围约束，params 含 min / max（数值常量，或引用其他列名/表达式）\n"
                + "- NON_NULL：NOT NULL 列不应有空值，params 含 default_value（可选，补值）\n"
                + "- FORMAT：列值应符合格式（如手机号/邮箱/URL），params 含 regex（正则表达式）\n"
                + "- CUSTOM_SQL：规则无法用固定类型表达时的最后手段（如\"已发货订单必须有物流单号\"），"
                + "params 含 check_sql（只读 SELECT，表名必须用 {schema} 占位符如 {schema}.orders，"
                + "结果必须含目标表 id 列如 SELECT t.id AS id）与可选 fix_sql（修复 SQL："
                + "UPDATE {schema}.表名 SET 列 = 值 WHERE id = {id}，表名必须等于规则表，"
                + "{id} 运行时绑定每条问题行 id；禁止 DELETE/DROP/INSERT、禁止无 WHERE 的全表 UPDATE），"
                + "confidence 标 <0.7\n"
                + "重要：每条规则的 expression 字段用一句完整的中文自然语言描述业务规则"
                + "（如'订单总额必须等于所有明细小计之和'、'订单状态绝大多数为已完成'），不要用英文或代码。\n"
                + "每规则给 confidence（0~1，低置信度标记优先人工确认）。\n"
                + "输出格式：{\"rules\": [{\"table\":\"...\",\"column\":\"...\",\"type\":\"...\","
                + "\"expression\":\"...\",\"confidence\":0.9,\"params\":{\"key\":\"value\"}}]}。\n"
                + "不要输出 markdown 代码块或解释文字。";

        try {
            ExtractedRules out = chatClient.prompt().system(system)
                    .user("库 `" + schemaName + "` 表结构：\n" + schemaSummary)
                    .call().entity(ExtractedRules.class);
            if (out != null && out.rules() != null && !out.rules().isEmpty()) {
                return out.rules().stream()
                        .map(r -> new com.dataforge.model.ProjectRules.Rule(
                                r.table(), r.column(), r.type(), r.expression(), r.confidence(),
                                com.dataforge.model.ProjectRules.Rule.Source.AGENT,
                                paramsToString(r.params())))
                        .toList();
            }
            log.warn("库 `{}` 规则提炼返回空 rules", schemaName);
        } catch (Exception e) {
            log.warn("库 `{}` 规则提炼失败（{}），回退内置默认规则", schemaName, e.getMessage());
        }
        return List.of();
    }

    /** 列工具决策输出契约：{"columns": [{"column","tool","params","confidence"}]} */
    public record ColumnDecisions(List<ColumnDecision> columns) {
    }

    public record ColumnDecision(String column, String tool, Map<String, Object> params, double confidence) {
    }

    /** 列工具决策：LLM 感知工具清单，为候选语义列选择生成方式（工具 + 参数，或 llm） */
    @Override
    public List<ColumnToolDecision> decideColumnTools(String tableName, List<ColumnDef> candidates,
                                                      String toolCatalog) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        String system = "你是数据生成列决策专家。根据工具清单和列语义，为每列选择生成方式（工具或 llm）。\n"
                + toolCatalog + "\n"
                + "原则：能用工具生成（关联复用/枚举/权重/日期/序号/格式/数值区间）就用工具并给出参数；"
                + "只有需要业务语义自由文本的列才选 llm。\n"
                + "输出严格 JSON {\"columns\": [{\"column\":\"...\",\"tool\":\"...\","
                + "\"params\":{\"key\":\"value\"},\"confidence\":0.9}]}。\n"
                + "tool 必须是工具清单中的名字或 llm。不要输出 markdown 代码块或解释文字。";
        String user = "表 `" + tableName + "` 候选列（需决策生成方式）：\n" + candidateSummary(candidates);
        try {
            ColumnDecisions out = chatClient.prompt().system(system).user(user)
                    .call().entity(ColumnDecisions.class);
            if (out != null && out.columns() != null && !out.columns().isEmpty()) {
                return out.columns().stream()
                        .map(cd -> ColumnToolDecision.of(cd.column(), cd.tool(),
                                paramsToString(cd.params()), cd.confidence()))
                        .toList();
            }
            log.warn("表 `{}` 列工具决策返回空，调用方兜底 llm", tableName);
        } catch (Exception e) {
            log.warn("表 `{}` 列工具决策失败（{}），调用方兜底 llm", tableName, e.getMessage());
        }
        return List.of();
    }

    /** 候选列摘要（列名/类型/COMMENT，喂 LLM 决策） */
    private static String candidateSummary(List<ColumnDef> candidates) {
        StringBuilder sb = new StringBuilder();
        for (ColumnDef c : candidates) {
            sb.append("- ").append(c.name()).append(" (").append(c.type()).append(")");
            if (c.comment() != null && !c.comment().isEmpty()) {
                sb.append(" — ").append(c.comment());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 语义打分输出契约：{"score": 0.85} */
    public record ScoreResult(double score) {
    }

    /** P7 语义打分：LLM 评估样本真实感（0-1），失败返回 -1（软评分仅统计距离） */
    @Override
    public double scoreSamples(String tableName, List<Map<String, Object>> samples) {
        String system = "你是数据质量评估专家。评估模拟数据样本的真实感（业务语义是否合理、"
                + "关联是否一致、值是否自然），输出 0~1 的分数（只输出严格 JSON {\"score\": 0.85}）。";
        String user = "表 `" + tableName + "` 样本行（前 " + Math.min(samples.size(), 10) + " 条）：\n"
                + samples.subList(0, Math.min(samples.size(), 10));
        try {
            ScoreResult out = chatClient.prompt().system(system).user(user)
                    .call().entity(ScoreResult.class);
            if (out != null) {
                return Math.max(0, Math.min(1, out.score()));
            }
        } catch (Exception e) {
            log.warn("表 `{}` 语义打分失败（{}）", tableName, e.getMessage());
        }
        return -1;
    }

    @Override
    public boolean isMock() {
        return false;
    }
}
