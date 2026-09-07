package com.dataforge.tools;

import java.util.List;

/**
 * 系统工具目录：把现有确定性能力注册为可编排工具（声明层）。
 *
 * <p>目录即 DataForge 的答辩/文档口径：修正环节 = <b>调 validate 工具拿分桶报告
 * → 决策（规则通道优先 + LLM 兜底）→ 调 fix 工具收敛 → 再 validate</b>，派生列 =
 * 调 derive 工具批量派生。</p>
 *
 * <p>当前实际执行仍走 {@code CorrectionPipeline} / {@code CorrectionEngine} /
 * {@code ExpansionEngine} 原路径，本目录只注册<b>契约</b>，不新建执行分支——
 * 这样既能把既有能力用统一工具语义对外讲清，也为将来「多步派生工作流 / Agent 工具循环」
 * 留出扩展点，且不引入回归风险。</p>
 */
public final class SystemToolRegistry {

    private SystemToolRegistry() {}

    /** 已注册的系统工具（增删改工具 = 在此增删条目，收敛循环代码不动） */
    private static final List<SystemTool> TOOLS = List.of(
            new SystemTool(
                    "validate", "校验",
                    "对 staging 库执行确定性校验，返回按问题类型(errorType)分桶的报告（含 count/ratio/样本行/根因）",
                    "tables, rules, schemaName",
                    "ValidationReport{buckets, totalErrors, elapsedMs}",
                    "SqlValidator.validate（RuleCompiler 编译 check SQL，全量扫描分桶）"),
            new SystemTool(
                    "fix", "修正",
                    "对校验报告的问题桶执行收敛修正：规则通道优先（AGG_EQUAL 重算/唯一去重/FK 重分配/范围钳制/补默认/FORMAT 重生成/CUSTOM_SQL 过闸门），LLM 兜底语义值；回归即回滚、通过率未上升早停",
                    "tables, data, initialReport, rules, schemaName",
                    "CorrectionResult{data, finalReport, converged, rolledBack, iterations, appliedFixes}",
                    "CorrectionEngine.run（手写 ReAct 收敛循环，id 锚定最小干预）"),
            new SystemTool(
                    "derive", "派生",
                    "按派生规则批量派生派生列（三路生成之一）：行内派生列由表达式 expr 计算，跨表反向聚合由 AGG_EQUAL 规则回填",
                    "expr: plan+table；聚合: data+aggRules",
                    "GeneratedData / boolean(applied)",
                    "GenericExpander + ExpressionEvaluator(expr) · ReconcileEngine(AGG_EQUAL 回填，一份规则两处复用)")
    );

    /** 已注册工具列表（只读） */
    public static List<SystemTool> tools() {
        return TOOLS;
    }

    /** 按名查找工具；未注册返回 null（供决策方校验，防编造工具名） */
    public static SystemTool find(String name) {
        if (name == null) return null;
        return TOOLS.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
    }

    /** 工具名是否已注册 */
    public static boolean isKnown(String name) {
        return find(name) != null;
    }

    /** 目录的 prompt/文档文本（中文，供分析/修正 Agent 感知系统工具清单） */
    public static String catalogText() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用的系统工具（校验/修正/派生契约）\n");
        for (SystemTool t : TOOLS) {
            sb.append("- ").append(t.name()).append(" 【").append(t.category()).append("】: ")
                    .append(t.description())
                    .append("（参数: ").append(t.paramsSchema())
                    .append("；返回: ").append(t.returns())
                    .append("；底层实现: ").append(t.impl()).append("）\n");
        }
        return sb.toString();
    }
}
