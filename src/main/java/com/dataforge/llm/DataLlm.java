package com.dataforge.llm;

import com.dataforge.model.ColumnDef;
import com.dataforge.model.ColumnToolDecision;
import com.dataforge.model.ProjectRules;

import java.util.List;
import java.util.Map;

/**
 * 数据生成 LLM 抽象（主备 failover 的接口位置，P8 实现双链路）。
 *
 * <p>职责边界（信任模型红线）：LLM 只负责生成<b>语义列</b>值（LLM 决策），
 * PK/FK/格式由确定性代码注入（规则执行）。</p>
 *
 * <p>实现：
 * <ul>
 *   <li>{@link RealLlmClient} — Spring AI ChatClient 结构化输出（OpenAI 兼容端点接 DeepSeek）</li>
 *   <li>{@link MockLlmClient} — 确定性干跑（--mock-llm / 单测），可注入故障验证降批/补差</li>
 * </ul>
 */
public interface DataLlm {

    /**
     * 生成一批行的 LLM 列值。
     *
     * @param tableName 表名（prompt 上下文用）
     * @param llmCols   需 LLM 生成的语义列
     * @param rowCount  期望行数
     * @param fkContext 父表上下文文本（FK 值 → 父表记录，可为 null）
     * @return 行列表（每行 Map 只含 llmCols 的列）；调用失败抛异常，由调用方降批/补差
     */
    List<Map<String, Object>> generateRows(String tableName, List<ColumnDef> llmCols,
                                           int rowCount, String fkContext);

    /**
     * 修正通道（P3）：对问题桶的样本行产出修正值（id 锚定，LLM 语义决策）。
     *
     * <p>信任模型红线不变：LLM 只判断"值改多少"（语义），修正执行仍由确定性 SQL 完成。
     * 默认不修正（返回空 → 该桶上报残留），实现按需覆盖。</p>
     *
     * @param tableName 目标表
     * @param columns   需修正的列（桶的 column）
     * @param samples   问题行快照（含 id，供 id 锚定决策）
     * @return 每行 {@code {"id": long, "<列名>": 修正值}}；空列表 = 无法修正
     */
    default List<Map<String, Object>> correctValues(String tableName, List<ColumnDef> columns,
                                                    List<Map<String, Object>> samples) {
        return List.of();
    }

    /**
     * 规则提炼通道（P6 分析 agent）：从表结构提炼业务规则（含置信度）。
     *
     * <p>信任模型红线：LLM 只<b>发现</b>规则（一次性 + 人工确认），规则<b>执行</b>由确定性代码
     * 编译（校验 SQL / 修正 SQL）。默认不提炼（返回空），实现按需覆盖。</p>
     *
     * @param schemaName    库名
     * @param schemaSummary 表结构紧凑文本（表/列/COMMENT/枚举/FK，分析 prompt 输入）
     * @return 提炼的规则（type: AGG_EQUAL / ENUM_WEIGHT / STATUS_FLOW / VALUE_RANGE ...，含置信度）
     */
    default List<ProjectRules.Rule> extractRules(String schemaName, String schemaSummary) {
        return List.of();
    }

    /**
     * 语义抽样打分（P7 软评分）：LLM 评估样本行的真实感（0-1）。
     * 与统计距离（KS/JS）互补——统计量"像不像"，语义判"合不合理"。
     * 默认 -1 = 不评估（软评分仅统计距离）。
     */
    default double scoreSamples(String tableName, List<Map<String, Object>> samples) {
        return -1;
    }

    /**
     * 列工具决策通道（工具感知列分类）：对候选语义列，参考工具清单决策每列生成方式
     * （工具 + 参数，或 llm）。
     *
     * <p>信任模型红线不变：LLM 只产出"选哪个工具"的<b>决策</b>，工具执行由确定性代码完成。
     * 默认空（→ 调用方 ColumnToolDecider 兜底为 llm），实现按需覆盖。</p>
     *
     * @param tableName   目标表
     * @param candidates  候选语义列（铁律列 PK/FK/ENUM/日期 已排除）
     * @param toolCatalog {@code ToolRegistry.catalogText()} 工具清单文本
     * @return 每列决策 {@code {column, tool, params, confidence}}；空 = 无决策（调用方兜底 llm）
     */
    default List<ColumnToolDecision> decideColumnTools(String tableName, List<ColumnDef> candidates,
                                                       String toolCatalog) {
        return List.of();
    }

    /**
     * 行数推荐（解析阶段轻量调用，不提炼规则）：从表结构推荐每表合理行数（按关联推导）。
     * 返回空 → 调用方用基础默认（如 10）。解析/分析职责分离：解析给行数、分析提炼规则。
     */
    default Map<String, Integer> recommendRows(String schemaSummary) {
        return Map.of();
    }

    /**
     * 规则修订（Web 人工检查点：用户通过 LLM 修改分析规则）。
     * 用户给自然语言指令（如"金额规则改成四舍五入到元""删掉状态联动规则"），
     * LLM 基于当前规则修订 → 返回新规则列表。默认返回当前（不修订）。
     */
    default List<ProjectRules.Rule> refineRules(List<ProjectRules.Rule> current, String instruction) {
        return current;
    }

    /** 是否 mock 实现（日志/缓存 key 区分用） */
    boolean isMock();
}
