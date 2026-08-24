package com.dataforge.llm;

import com.dataforge.model.ColumnDef;
import com.dataforge.model.ColumnToolDecision;
import com.dataforge.model.ProjectRules;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 确定性 Mock LLM（--mock-llm 干跑 / 单测注入）。
 *
 * <p>不调用任何外部服务：按列类型生成确定性占位值。值用<b>全局递增计数器</b>
 * （跨批次/跨表不重复——模拟真实 LLM 的全局视野，避免批次并行导致唯一键冲突），
 * 用于验收管线正确性（DAG 调度 / FK 注入 / 并发 / 补差 / 降批 / 缓存 / 断点）。</p>
 *
 * <p>故障注入（单测验证 batch 自适应 / 补差不重试 / 失败表隔离）：
 * <ul>
 *   <li>{@link #failOnRowCount}：指定行数请求抛异常 → 触发 batch 自适应降批</li>
 *   <li>{@link #failOnTable}：指定表所有请求抛异常 → 模拟持续失败 → 失败表隔离</li>
 *   <li>{@link #shortfallRatio}：返回行数 = 期望 × (1 - ratio) → 触发补差不重试</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "dataforge.mock-llm", havingValue = "true")
public class MockLlmClient implements DataLlm {

    private final Map<Integer, RuntimeException> failOnRowCount = new HashMap<>();
    private final Map<String, RuntimeException> failOnTable = new HashMap<>();
    private double shortfallRatio = 0.0;
    /** 全局递增计数器：保证 mock 值跨批次/跨表唯一（批次并行的唯一键冲突由 LLM 副作用引起） */
    private final java.util.concurrent.atomic.AtomicLong globalSeq = new java.util.concurrent.atomic.AtomicLong(1);

    /** 单测注入：指定行数的请求抛异常（模拟 LLM 超时/解析失败 → batch 自适应降批） */
    public MockLlmClient failOnRowCount(int rowCount, RuntimeException e) {
        failOnRowCount.put(rowCount, e);
        return this;
    }

    /** 单测注入：指定表的所有请求抛异常（模拟持续失败 → 失败表隔离） */
    public MockLlmClient failOnTable(String tableName, RuntimeException e) {
        failOnTable.put(tableName, e);
        return this;
    }

    /** 单测注入：返回行数缺额比例（模拟 LLM 截断 → 补差不重试） */
    public MockLlmClient shortfallRatio(double ratio) {
        this.shortfallRatio = ratio;
        return this;
    }

    @Override
    public List<Map<String, Object>> generateRows(String tableName, List<ColumnDef> llmCols,
                                                  int rowCount, String fkContext) {
        RuntimeException fault = failOnRowCount.get(rowCount);
        if (fault != null) throw fault;
        fault = failOnTable.get(tableName);
        if (fault != null) throw fault;

        int target = (int) Math.round(rowCount * (1 - shortfallRatio));
        List<Map<String, Object>> rows = new ArrayList<>(target);
        for (int i = 0; i < target; i++) {
            long seq = globalSeq.getAndIncrement();
            Map<String, Object> row = new HashMap<>();
            for (ColumnDef col : llmCols) {
                row.put(col.name(), mockValue(col, seq));
            }
            rows.add(row);
        }
        return rows;
    }

    /** 按列类型生成占位值（全局序号保证唯一；name 类列用真实中文名池——mock 数据也像样） */
    private Object mockValue(ColumnDef col, long seq) {
        String type = col.normalizedType();
        if (type.startsWith("DECIMAL") || type.startsWith("NUMERIC")
                || type.startsWith("FLOAT") || type.startsWith("DOUBLE")) {
            return Math.round((50.0 + seq % 10000) * 100.0) / 100.0;
        }
        if (type.startsWith("INT") || type.startsWith("SMALLINT")
                || type.startsWith("TINYINT") || type.startsWith("BIGINT")) {
            return (int) (seq % 100000) + 1;
        }
        if (type.startsWith("DATE") || type.startsWith("TIME") || "TIMESTAMP".equals(type)) {
            return "2026-01-01 00:00:00";
        }
        if (type.equals("JSON") || type.equals("JSONB")) {
            return "{}";
        }
        // 字符串/文本
        String name = col.name().toLowerCase();
        if (name.contains("phone") || name.contains("mobile")) {
            return com.dataforge.expansion.ValuePools.randomPhone(new java.util.Random(seq));
        }
        if (name.contains("name") || name.contains("nickname") || name.contains("title")
                || name.contains("receiver") || name.contains("contact")) {
            return com.dataforge.expansion.ValuePools.randomChineseName(new java.util.Random(seq));
        }
        if (name.contains("email")) {
            return "user" + (seq % 100000) + "@qq.com";
        }
        return "mock_" + col.name() + "_" + seq;
    }

    @Override
    public List<Map<String, Object>> correctValues(String tableName, List<ColumnDef> columns,
                                                   List<Map<String, Object>> samples) {
        if (columns.isEmpty() || samples.isEmpty()) return List.of();
        ColumnDef col = columns.get(0);
        List<Map<String, Object>> fixes = new ArrayList<>(samples.size());
        for (Map<String, Object> sample : samples) {
            Object idVal = sample.get("id");
            if (idVal == null) continue;
            long id = ((Number) idVal).longValue();
            Map<String, Object> fix = new HashMap<>();
            fix.put("id", id);
            // mock 修正值：确定性占位（基于 id 的全局序号保证不与其他冲突）
            fix.put(col.name(), mockValue(col, globalSeq.getAndIncrement()));
            fixes.add(fix);
        }
        return fixes;
    }

    /**
     * Mock 列工具决策（工具感知分类）：确定性启发式——对候选语义列按类型/列名选择生成工具，
     * 模拟真实 LLM 的工具感知决策（验收分析 agent 链路；真实模式走 RealLlmClient）。
     *
     * <p>决策方向：日期→date；数值→valueRange；格式字符串（人名/地址/电话等）→faker；
     * 其余业务语义自由文本 → llm。source 表白名单过滤由 ColumnToolDecider 负责。</p>
     */
    @Override
    public List<ColumnToolDecision> decideColumnTools(String tableName, List<ColumnDef> candidates,
                                                      String toolCatalog) {
        List<ColumnToolDecision> decisions = new ArrayList<>();
        for (ColumnDef c : candidates) {
            String name = c.name().toLowerCase();
            String type = c.normalizedType();
            // 日期 → date
            if (type.startsWith("DATE") || type.startsWith("TIME") || type.startsWith("YEAR")
                    || "TIMESTAMP".equals(type)) {
                decisions.add(ColumnToolDecision.of(c.name(), "date",
                        java.util.Map.of("range", "-90d,0d"), 0.9));
                continue;
            }
            // 数值枚举（status/type/deleted/level，COMMENT 含枚举）→ weighted
            if (isNumericEnumLike(c)) {
                decisions.add(ColumnToolDecision.of(c.name(), "weighted",
                        java.util.Map.of("weights", dominantWeights(c)), 0.8));
                continue;
            }
            // 数值 → valueRange
            if (isNumericType(type)) {
                decisions.add(ColumnToolDecision.of(c.name(), "valueRange", java.util.Map.of(), 0.6));
                continue;
            }
            // 格式字符串 → faker
            String fakerType = mockFakerType(name);
            if (fakerType != null) {
                decisions.add(ColumnToolDecision.of(c.name(), "faker",
                        java.util.Map.of("type", fakerType), 0.9));
                continue;
            }
            // 其余业务语义自由文本 → llm（无工具可覆盖）
            decisions.add(ColumnToolDecision.of(c.name(), "llm", java.util.Map.of(), 0.5));
        }
        return decisions;
    }

    private static boolean isNumericType(String type) {
        if (type == null) return false;
        return type.startsWith("INT") || type.startsWith("BIGINT") || type.startsWith("SMALLINT")
                || type.startsWith("TINYINT") || type.startsWith("MEDIUMINT")
                || type.startsWith("DECIMAL") || type.startsWith("NUMERIC")
                || type.startsWith("FLOAT") || type.startsWith("DOUBLE");
    }

    /** 数值枚举列：数值类型 + 枚举语义列名或 COMMENT 数字枚举（与 ColumnClassifier 判定一致） */
    private static boolean isNumericEnumLike(ColumnDef c) {
        String type = c.normalizedType();
        boolean numeric = type.startsWith("TINYINT") || type.startsWith("SMALLINT")
                || type.startsWith("MEDIUMINT") || type.startsWith("INT");
        if (!numeric) return false;
        String name = c.name().toLowerCase();
        if (name.contains("status") || name.contains("state") || name.contains("deleted")
                || name.contains("enable") || name.contains("level") || name.contains("type")
                || name.contains("flag") || name.equals("sort") || name.equals("quantity")) {
            return true;
        }
        String comment = c.comment() != null ? c.comment() : "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\d+\\s*[\\u4e00-\\u9fa5A-Za-z]").matcher(comment);
        int count = 0;
        while (m.find() && count < 3) count++;
        return count >= 2;
    }

    /** dominant 权重串："绝大多数为 4" → "4:0.85"（模拟 LLM 提炼权重） */
    private static String dominantWeights(ColumnDef c) {
        String comment = c.comment() != null ? c.comment() : "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(绝大多数|大部分|全部|默认)\\s*(?:为|是)?\\s*(\\d+)").matcher(comment);
        if (m.find()) {
            return m.group(2) + ":0.85";
        }
        return "0:0.85";
    }

    /** 格式字符串列 → faker 类型（与 PlanBuilder.fakerType 语义一致） */
    private static String mockFakerType(String nameLower) {
        if (nameLower.contains("phone") || nameLower.contains("mobile")) return "phone";
        if (nameLower.contains("email")) return "email";
        if (nameLower.contains("address")) return "address";
        if (nameLower.contains("remark") || nameLower.contains("note")
                || nameLower.contains("comment")) return "remark";
        if (nameLower.contains("logistics")) return "logistics";
        if (nameLower.startsWith("receiver") || nameLower.startsWith("contact")
                || nameLower.contains("nickname") || nameLower.contains("member_name")
                || nameLower.contains("user_name") || nameLower.contains("username")) return "name";
        return null;
    }

    /**
     * Mock 规则提炼（P6）：确定性启发式——从表结构文本检测典型约束模式，
     * 模拟真实 LLM 的规则判断（验收分析 agent 链路；真实模式走 RealLlmClient）。
     */
    @Override
    public List<ProjectRules.Rule> extractRules(String schemaName, String schemaSummary) {
        List<ProjectRules.Rule> rules = new ArrayList<>();
        String s = schemaSummary;
        // 金额对账（AGG_EQUAL）：目标列 = 引用表按 join 键求和
        if (s.contains("total_amount") && s.contains("subtotal") && s.contains("order_item")) {
            rules.add(ProjectRules.Rule.aggEqual("orders", "total_amount", "order_item", "subtotal",
                    "order_id", "总额 = Σ 明细小计", ProjectRules.Rule.Source.AGENT));
        }
        // 状态权重（ENUM_WEIGHT）：状态列 COMMENT 描述枚举，绝大多数已完成
        if (s.contains("status") && (s.contains("已完成") || s.contains("完成"))) {
            rules.add(new ProjectRules.Rule("orders", "status", "ENUM_WEIGHT",
                    "状态绝大多数为 4 已完成", 0.8, ProjectRules.Rule.Source.AGENT,
                    Map.of("weights", "4:0.85")));
        }
        // 状态联动（STATUS_FLOW）：某表含 status 且 ≥2 个日期列 → 时间线联动（通用启发式，跨域可用）
        String statusTable = null;
        boolean hasStatus = false;
        String currentTable = null;
        List<String> dateCols = new ArrayList<>();
        for (String line : s.split("\n")) {
            String t = line.trim();
            java.util.regex.Matcher tm = java.util.regex.Pattern.compile("^表 (\\w+):").matcher(t);
            if (tm.find()) {
                flushStatusFlow(statusTable, hasStatus, dateCols, rules);
                currentTable = tm.group(1);
                statusTable = null;
                hasStatus = false;
                dateCols = new ArrayList<>();
                continue;
            }
            if (currentTable != null && t.contains("status") && statusTable == null) {
                statusTable = currentTable;
                hasStatus = true;
            }
            java.util.regex.Matcher dm = java.util.regex.Pattern
                    .compile("(\\w+)\\s*\\((date|datetime|timestamp|time)\\)",
                            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t);
            if (dm.find()) dateCols.add(dm.group(1));
        }
        flushStatusFlow(statusTable, hasStatus, dateCols, rules);
        // 价格顺序（VALUE_RANGE）：original_price ≥ price
        if (s.contains("original_price") && s.contains("price")) {
            rules.add(new ProjectRules.Rule("product", "original_price", "VALUE_RANGE",
                    "original_price ≥ price 且比率 ≤ 5", 0.6,
                    ProjectRules.Rule.Source.AGENT, Map.of("min", "price", "max", "price*5")));
        }
        return rules;
    }

    /** 结算：某表含 status 且 ≥2 日期列 → 提炼 STATUS_FLOW 规则（flow=日期列列表） */
    private static void flushStatusFlow(String statusTable, boolean hasStatus,
                                        List<String> dateCols, List<ProjectRules.Rule> rules) {
        if (statusTable != null && hasStatus && dateCols.size() >= 2) {
            rules.add(new ProjectRules.Rule(statusTable, "status", "STATUS_FLOW",
                    "状态为已完成时有完整时间线（时间列严格递增）", 0.7,
                    ProjectRules.Rule.Source.AGENT,
                    java.util.Map.of("flow", String.join(",", dateCols))));
        }
    }

    /** Mock 语义打分：确定性 0.85（验收软评分链路；真实模式走 RealLlmClient） */
    @Override
    public double scoreSamples(String tableName, List<Map<String, Object>> samples) {
        return 0.85;
    }

    /** Mock 行数推荐：确定性启发式（按表出现顺序给 10/20/30，模拟 LLM 推荐） */
    @Override
    public Map<String, Integer> recommendRows(String schemaSummary) {
        Map<String, Integer> rec = new HashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("表 (\\w+):").matcher(schemaSummary);
        int idx = 0;
        while (m.find()) {
            rec.put(m.group(1), 10 + (idx++ % 3) * 10);
        }
        return rec;
    }

    /** Mock 规则修订（P6 人工检查点 + HITL）：确定性启发式——按指令关键词增删规则 */
    @Override
    public List<ProjectRules.Rule> refineRules(List<ProjectRules.Rule> current, String instruction) {
        String ins = instruction == null ? "" : instruction;
        List<ProjectRules.Rule> rules = new ArrayList<>(current);
        // 删除指令："删/去/不要" + 目标
        if (ins.contains("删") || ins.contains("去") || ins.contains("不要")) {
            rules.removeIf(r -> (ins.contains("金额") && r.type().equals("AGG_EQUAL"))
                    || (ins.contains("状态") && (r.type().equals("ENUM_WEIGHT")
                    || r.type().equals("STATUS_FLOW")))
                    || (ins.contains("价格") && r.type().equals("VALUE_RANGE")));
        }
        // 金额规则：提到金额且没有 → 补 AGG_EQUAL
        if (ins.contains("金额") && rules.stream().noneMatch(r -> r.type().equals("AGG_EQUAL"))) {
            rules.add(ProjectRules.Rule.aggEqual("orders", "total_amount", "order_item", "subtotal",
                    "order_id", "总额 = Σ 明细小计（用户确认）", ProjectRules.Rule.Source.USER_CONFIRMED));
        }
        // 状态规则：提到状态且没有 → 补 ENUM_WEIGHT
        if (ins.contains("状态") && rules.stream().noneMatch(r -> r.type().equals("ENUM_WEIGHT"))) {
            rules.add(new ProjectRules.Rule("orders", "status", "ENUM_WEIGHT",
                    "状态绝大多数为 4 已完成（用户确认）", 0.9,
                    ProjectRules.Rule.Source.USER_CONFIRMED, Map.of("weights", "4:0.85")));
        }
        // 其他指令：追加一条用户备注规则（USER_OVERRIDE，用户声明 > agent 推断）
        if (!ins.isBlank() && !ins.contains("删") && !ins.contains("去")
                && !ins.contains("金额") && !ins.contains("状态")) {
            rules.add(new ProjectRules.Rule("custom", "custom", "USER_NOTE", ins, 1.0,
                    ProjectRules.Rule.Source.USER_OVERRIDE, Map.of()));
        }
        return rules;
    }

    @Override
    public boolean isMock() {
        return true;
    }
}
