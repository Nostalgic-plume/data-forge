package com.dataforge.expansion;

import java.util.List;

/**
 * 生成工具注册表（工具感知列分类决策的核心）：把 {@link GenericExpander} 支持的列生成方式
 * 声明为"工具"，供分析 agent 感知——LLM 决策每列时，能匹配工具就给参数走代码生成，匹配不了走 llm。
 *
 * <p>四类分组（决定工具语义与决策方向）：
 * <ul>
 *   <li><b>复用型</b>：fk（引用父表已生成 id 池，关联复用）</li>
 *   <li><b>锚点加工型</b>：anchorPrice（父表锚点列 × 系数）</li>
 *   <li><b>纯生成型</b>：seq/weighted/enum/pool/date/unique/faker/valueRange/const（规则派生新值）</li>
 *   <li><b>语义型</b>：llm（LLM 生成，无工具可覆盖时的兜底）</li>
 * </ul>
 *
 * <p>信任模型红线：ToolRegistry 只做<b>能力声明</b>，不实现任何生成逻辑——
 * 执行仍由 GenericExpander / RuleDataGenerator 确定性完成。</p>
 */
public final class ToolRegistry {

    private ToolRegistry() {}

    /** 工具定义：名称 / 类别 / 功能 / 参数说明 / 适用条件 / 示例 */
    public record ToolDef(
            String name,
            String category,
            String description,
            String paramsSchema,
            String applicability,
            String example
    ) {
    }

    private static final List<ToolDef> TOOLS = List.of(
            // ---- 复用型 ----
            new ToolDef("fk", "复用型", "引用父表已生成 id 池（关联复用，zipf 幂律长尾）",
                    "ref_table, ref_column(默认 id), zipf(true=幂律)",
                    "真实外键列（必须在表的外键定义中）",
                    "order_item.user_id → fk(user.id, zipf=true)"),
            // ---- 锚点加工型 ----
            new ToolDef("anchorPrice", "锚点加工型", "父表锚点列 × 系数（锚点派生）",
                    "ref_table, ref_column(默认 price), coefficients(如 0.8,1.0,1.2)",
                    "数值价格列且父表存在锚点列",
                    "sku.price = product.price × 0.8/1.0/1.2"),
            // ---- 纯生成型 ----
            new ToolDef("seq", "纯生成型", "前缀+序号（增量自动续号不冲突）",
                    "prefix, width(默认 6)",
                    "业务唯一序号列（order_no/coupon_no 等）",
                    "order_no = ORD-000001"),
            new ToolDef("weighted", "纯生成型", "权重分布（值:权重，绝大多数为某值）",
                    "weights(如 4:0.85,0:0.05)",
                    "状态/枚举数值列（status/type/deleted）",
                    "orders.status = 4 占 85%"),
            new ToolDef("enum", "纯生成型", "枚举值随机（DB ENUM 或源码枚举）",
                    "values(可选，逗号分隔)",
                    "显式枚举列",
                    "gender = 男/女"),
            new ToolDef("pool", "纯生成型", "值池随机",
                    "values(A,B,C)",
                    "有限取值集合的字符串列",
                    "level = 初级/中级/高级"),
            new ToolDef("date", "纯生成型", "日期区间（相对今天）",
                    "range(如 -90d,0d)",
                    "日期时间列",
                    "create_time 在过去 90 天内"),
            new ToolDef("unique", "纯生成型", "与已填值唯一（前缀自增）",
                    "prefix",
                    "唯一业务编码列",
                    "coupon_no 不重复"),
            new ToolDef("faker", "纯生成型", "通用值生成（中文名/电话/地址/邮箱等）",
                    "type(name/phone/address/email/remark/logistics)",
                    "格式固定字符串列（人名/地址/电话）",
                    "receiver = 张三"),
            new ToolDef("valueRange", "纯生成型", "数值区间随机",
                    "min, max",
                    "数值列但非枚举",
                    "score 在 0~100"),
            new ToolDef("const", "纯生成型", "常量",
                    "value",
                    "固定值列（deleted=0 等）",
                    "deleted = 0"),
            // ---- 语义型 ----
            new ToolDef("llm", "语义型", "LLM 生成语义值（无工具可覆盖时的兜底）",
                    "无",
                    "业务语义列（名称/标题/描述/正文）",
                    "product_name = 品牌+型号")
    );

    public static List<ToolDef> tools() {
        return TOOLS;
    }

    /** 工具名是否已知（含语义型 llm；决策校验用，防 LLM 编造工具名） */
    public static boolean isKnownTool(String name) {
        if (name == null) return false;
        return "llm".equals(name) || TOOLS.stream().anyMatch(t -> t.name().equals(name));
    }

    /** 该列生成方式是否为"LLM 语义生成" */
    public static boolean isLlm(String name) {
        return "llm".equals(name);
    }

    /** 四类分组格式化的工具清单（喂 LLM 决策的 prompt 文本） */
    public static String catalogText() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用的列生成工具（分析 agent 决策依据）\n");
        sb.append("为每列选择一种生成方式：能用工具生成就用工具并给出参数，只有需要业务语义自由文本的列才选 llm。\n");
        String current = null;
        for (ToolDef t : TOOLS) {
            if (!t.category().equals(current)) {
                current = t.category();
                sb.append("\n【").append(current).append("】\n");
            }
            sb.append("- ").append(t.name()).append(": ").append(t.description())
                    .append("（参数: ").append(t.paramsSchema())
                    .append("；适用: ").append(t.applicability())
                    .append("；示例: ").append(t.example()).append("）\n");
        }
        return sb.toString();
    }
}
