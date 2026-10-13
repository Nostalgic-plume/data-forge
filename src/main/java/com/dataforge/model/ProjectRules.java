package com.dataforge.model;

import java.util.List;
import java.util.Map;

/**
 * 项目规则母版（API_CONTRACT §2.3，对应 project_rules.md）。
 * P0 无分析 agent，此类型先落地契约（空规则跑管线）；P6 分析 agent 产出。
 *
 * @param generationRules 前置约束节（注入生成 prompt）
 * @param correctionRules 后置修正规则节（编译成校验 SQL，P3 用）
 * @param builders        行构建器（P1 展开器执行）
 * @param version         缓存/增量版本号
 */
public record ProjectRules(
        List<Rule> generationRules,
        List<Rule> correctionRules,
        List<BuilderDef> builders,
        int version
) {
    /**
     * 规则（P4 通用化：引擎按 type 编译，业务参数全部走 params，不写死表名）。
     *
     * <p>type（引擎可编译的类型）：
     * <ul>
     *   <li>AGG_EQUAL — 聚合相等：{@code target.column = SUM(ref_table.sum_col GROUP BY join_col 对 target.id)}，
     *       params: {@code ref_table}（引用表）、{@code sum_col}（求和列）、{@code join_col}（ref 表关联 target.id 的列）</li>
     *   <li>FK / UNIQUE — 通用规则，由 DDL 元数据自动推导，不入 correctionRules</li>
     *   <li>CUSTOM_SQL — 自定义校验（LLM 产 SQL）：params 含 {@code check_sql}（只读 SELECT，表名用
     *       {@code {schema}} 占位符，结果必须含目标表 id 列）与可选 {@code fix_sql}（修复 UPDATE：
     *       {@code UPDATE {schema}.表 SET ... WHERE id = {id}}，id 锚定防全表 UPDATE）、
     *       {@code fix_column}（修正目标列，默认 rule.column()）。过 {@code SqlGate} 结构闸门后执行</li>
     * </ul>
     * P6 扩展：FORMULA / STATUS_FLOW / VALUE_RANGE / ENUM_WEIGHT。</p>
     */
    public record Rule(
            String table,
            String column,
            String type,
            String expression,
            double confidence,
            Source source,
            Map<String, String> params
    ) {
        public enum Source { AGENT, USER_CONFIRMED, USER_OVERRIDE }

        /** 便捷构造（无结构化参数，P6 简单规则/占位用） */
        public static Rule simple(String table, String column, String type, String expression,
                                  double confidence, Source source) {
            return new Rule(table, column, type, expression, confidence, source, Map.of());
        }

        /** 聚合相等规则（金额对账等）：target.column = SUM(ref_table.sum_col GROUP BY join_col) */
        public static Rule aggEqual(String table, String column, String refTable, String sumCol,
                                    String joinCol, String expression, Source source) {
            return new Rule(table, column, "AGG_EQUAL", expression, 1.0, source,
                    Map.of("ref_table", refTable, "sum_col", sumCol, "join_col", joinCol));
        }
    }

    /** 行构建器（YAML，展开器执行） */
    public record BuilderDef(
            String table,
            String templateRef,
            String anchorColumn
    ) {
    }

    /** 空规则（P0 默认：无分析产物时管线照常跑） */
    public static ProjectRules empty() {
        return new ProjectRules(List.of(), List.of(), List.of(), 0);
    }

    /**
     * 内置默认修正规则（P4 通用化：金额对账以参数化 AGG_EQUAL 表达，替代引擎硬编码 orders）。
     * P6 分析 agent 产出 project_rules 后由 correctionRules 替换。
     *
     * <p>金额链三段在<b>没有 project_rules.json 时也必须自洽</b>：total_amount 由 AGG_EQUAL
     * 汇总明细，actual_amount 由 EXPR_EQUAL 行内推导——缺了后者，actual 会静默留展开器的
     * 占位 0（这正是删除 Reconciliation 兜底后最危险的一处）。</p>
     */
    public static ProjectRules withDefaultCorrectionRules() {
        Rule total = Rule.aggEqual("orders", "total_amount", "order_item", "subtotal",
                "order_id", "总额 = Σ 明细小计", Rule.Source.USER_CONFIRMED);
        Rule actual = new Rule("orders", "actual_amount", "EXPR_EQUAL",
                "实付金额 = 订单金额 - 优惠金额 + 运费", 1.0, Rule.Source.USER_CONFIRMED,
                Map.of("expr", "total_amount - discount_amount + freight"));
        return new ProjectRules(List.of(), List.of(total, actual), List.of(), 1);
    }
}
