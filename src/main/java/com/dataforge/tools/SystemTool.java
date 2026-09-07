package com.dataforge.tools;

/**
 * 系统能力工具契约（叙事 / 扩展层，不新增第二条执行路径）。
 *
 * <p>把 DataForge 的确定性能力——校验 / 修正 / 派生——声明为「可被编排调用」的系统级工具，
 * 与生成侧 {@link com.dataforge.expansion.ToolRegistry}（列级生成工具）互补：
 * 这里声明的是跨表/系统级工具，供修正 Agent、分析 Agent 以「工具调用」的语义描述与扩展。</p>
 *
 * <p>信任模型红线：工具只做<b>能力声明与参数契约</b>，实际执行永远由确定性实现完成
 * （{@code SqlValidator} / {@code CorrectionEngine} / {@code ExpansionEngine}）。
 * LLM 只做工具选择与传参，不参与判定。</p>
 *
 * @param name         工具名（validate / fix / derive）
 * @param category     工具类别（校验 / 修正 / 派生）
 * @param description  能力描述（喂 prompt / 文档用）
 * @param paramsSchema 参数契约
 * @param returns      返回契约
 * @param impl         底层确定性实现（当前执行委托方，审计/面试可追踪）
 */
public record SystemTool(
        String name,
        String category,
        String description,
        String paramsSchema,
        String returns,
        String impl
) {
}
