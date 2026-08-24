package com.dataforge.analysis;

import com.dataforge.model.ColumnDef;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则质量门禁（数据真实性，代码层根治垃圾规则）。
 *
 * <p>过滤 LLM 提炼的 generationRules 中的垃圾规则，避免落盘 project_rules.json 后
 * 展示误导 + 注入生成 prompt 污染 LLM（PromptBuilder 读 currentRules）：
 * <ul>
 *   <li><b>宽范围 VALUE_RANGE</b>（max-min &gt; 1_000_000，如金额 total_amount 0~1e8）→ 剔除</li>
 *   <li><b>数值枚举列 VALUE_RANGE</b>（status/type/deleted 等）→ 剔除（取值域由启发式 weighted/enum 覆盖）</li>
 * </ul>
 * 不影响 correctionRules（STATUS_FLOW / AGG_EQUAL 是合理校验规则）。</p>
 */
public final class RuleFilter {

    /** 宽范围护栏：跨度过大视为 LLM 占位大值（金额/日期类无意义宽区间） */
    private static final double MAX_RANGE_SPAN = 1_000_000;

    private RuleFilter() {}

    /**
     * 过滤 generationRules。
     *
     * @param rules  LLM 提炼的规则（含垃圾）
     * @param tables 表结构（判断数值枚举列）
     * @return 通过质量门禁的规则
     */
    public static List<ProjectRules.Rule> filterGeneration(
            List<ProjectRules.Rule> rules, List<TableMeta> tables) {
        if (rules == null || rules.isEmpty()) return rules == null ? List.of() : rules;
        Map<String, Set<String>> enumColumns = enumColumns(tables);
        return rules.stream().filter(r -> keep(r, enumColumns)).toList();
    }

    private static boolean keep(ProjectRules.Rule r, Map<String, Set<String>> enumColumns) {
        if (!"VALUE_RANGE".equals(r.type())) return true;   // 非 VALUE_RANGE 保留（ENUM_WEIGHT/STATUS_FLOW/USER_NOTE…）
        Double min = parseDouble(r.params().get("min"));
        Double max = parseDouble(r.params().get("max"));
        // 金额/价格列：真实约束来自对账（Reconciliation）/锚点，不用 LLM 的宽范围——
        //   必须给合理上界（无 max 或范围过宽 → 过滤）
        if (isMoneyColumn(r.column())) {
            if (max == null || (min != null && (max - min) > MAX_RANGE_SPAN)) return false;
        } else if (min != null && max != null && (max - min) > MAX_RANGE_SPAN) {
            return false;   // 非金额列宽范围
        }
        Set<String> cols = enumColumns.get(r.table());
        if (cols != null && cols.contains(r.column())) return false;                     // 数值枚举列
        return true;
    }

    /** 金额/价格类列（amount/price/fee/freight/discount/total） */
    private static boolean isMoneyColumn(String column) {
        if (column == null) return false;
        String n = column.toLowerCase();
        return n.contains("amount") || n.contains("price") || n.contains("fee")
                || n.contains("freight") || n.contains("discount") || n.contains("total");
    }

    /** 表 → 数值枚举列名集合（与 PlanBuilder.isNumericEnum 判定一致） */
    private static Map<String, Set<String>> enumColumns(List<TableMeta> tables) {
        Map<String, Set<String>> map = new HashMap<>();
        if (tables == null) return map;
        for (TableMeta t : tables) {
            for (ColumnDef c : t.columns()) {
                if (isNumericEnum(c)) {
                    map.computeIfAbsent(t.name(), k -> new HashSet<>()).add(c.name());
                }
            }
        }
        return map;
    }

    /** 数值枚举/开关列：数值类型 + 枚举语义列名 */
    static boolean isNumericEnum(ColumnDef c) {
        String name = c.name().toLowerCase();
        String type = c.normalizedType();
        boolean numeric = type.startsWith("TINYINT") || type.startsWith("SMALLINT")
                || type.startsWith("MEDIUMINT") || type.startsWith("INT");
        boolean enumLike = name.contains("status") || name.contains("state")
                || name.contains("type") || name.contains("level") || name.contains("deleted")
                || name.contains("gender") || name.contains("category");
        return numeric && enumLike;
    }

    private static Double parseDouble(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
