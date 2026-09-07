package com.dataforge.derivation;

import com.dataforge.model.ColumnDef;
import com.dataforge.model.DataRow;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.SourceTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用聚合回填器（derive-universal-design.md §H7/§3.5）：生成侧消费 {@code AGG_EQUAL} 修正规则，
 * 在内存数据上做跨表反向聚合回填。
 *
 * <p>语义：{@code target.column = SUM(ref_table.sum_col GROUP BY ref_table.join_col 对 target.id)}，
 * 无明细匹配的 target 行填 0（与 Reconciliation 现有口径一致）。一份 AGG_EQUAL 规则两处复用：
 * 本引擎在<b>生成后</b>回填，修正侧 SqlValidator/CorrectionEngine 在 staging 复核。
 * 表名/列名全部来自规则 params，引擎不持有业务知识（§H1 无语义）。</p>
 *
 * <p>显式约束（§H7）：只处理 AGG_EQUAL 且 ref/target 表都在 data 内的规则；缺表/缺参 →
 * warn 跳过并返回是否至少成功回填一处（调用方据此决定是否跳过 legacy total 段）。
 * 覆盖值血缘 CORRECTED，按目标列 DECIMAL scale 收口。</p>
 */
public final class ReconcileEngine {

    private static final Logger log = LoggerFactory.getLogger(ReconcileEngine.class);

    private ReconcileEngine() {}

    /**
     * 对内存数据执行 AGG_EQUAL 聚合回填。
     *
     * @param data     已生成的各表数据（tableName → GeneratedData）
     * @param aggRules AGG_EQUAL 修正规则（可为 null/空）
     * @return 是否至少成功回填一处（调用方可据此跳过 legacy Reconciliation 的 total 段）
     */
    public static boolean apply(Map<String, GeneratedData> data, List<ProjectRules.Rule> aggRules) {
        if (data == null || data.isEmpty() || aggRules == null || aggRules.isEmpty()) {
            return false;
        }
        boolean appliedAny = false;
        for (ProjectRules.Rule rule : aggRules) {
            if (rule == null || !"AGG_EQUAL".equals(rule.type())) continue;
            if (rule.table() == null || rule.column() == null) continue;
            GeneratedData target = data.get(rule.table());
            String refTable = rule.params() == null ? null : rule.params().get("ref_table");
            String sumCol = rule.params() == null ? null : rule.params().get("sum_col");
            String joinCol = rule.params() == null ? null : rule.params().get("join_col");
            GeneratedData ref = refTable == null ? null : data.get(refTable);
            if (target == null || ref == null) {
                log.info("聚合回填跳过 {}:{}：ref/target 表不在生成集（ref={}, target={}）",
                        rule.table(), rule.column(), refTable, rule.table());
                continue;
            }
            if (sumCol == null || joinCol == null || sumCol.isBlank() || joinCol.isBlank()) {
                log.warn("聚合回填跳过 {}:{}：规则缺 sum_col/join_col 参数", rule.table(), rule.column());
                continue;
            }
            appliedAny |= fill(target, rule.column(), ref, sumCol, joinCol);
        }
        return appliedAny;
    }

    /** 单条规则：ref 按 join_col 分组求和 → 回填 target 每行（命中组=sum，无组=0） */
    private static boolean fill(GeneratedData target, String targetColumn,
                                GeneratedData ref, String sumCol, String joinCol) {
        if (target.rows().isEmpty() || ref.rows().isEmpty()) return false;
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (DataRow row : ref.rows()) {
            Object joinVal = row.values().get(joinCol);
            if (joinVal == null) continue;
            BigDecimal add = toBigDecimal(row.values().get(sumCol));
            if (add == null) {
                log.warn("聚合回填：`{}`.`{}` 行值非数值，跳过该行", ref.tableName(), sumCol);
                continue;
            }
            sums.merge(joinVal.toString(), add, BigDecimal::add);
        }
        String idCol = idColumn(target);
        if (idCol == null) {
            log.warn("聚合回填跳过 {}:{}：目标表无主键/id 列", target.tableName(), targetColumn);
            return false;
        }
        int scale = targetScale(target, targetColumn);
        boolean filled = false;
        for (DataRow row : target.rows()) {
            Object idVal = row.values().get(idCol);
            if (idVal == null) continue;
            BigDecimal total = sums.get(idVal.toString());
            BigDecimal value = (total == null ? BigDecimal.ZERO : total).setScale(scale, RoundingMode.HALF_UP);
            row.values().put(targetColumn, value);
            row.lineage().put(targetColumn, SourceTag.CORRECTED);
            filled = true;
        }
        return filled;
    }

    /** 目标表主键列名：primaryKey 列优先，其次名为 id 的列 */
    private static String idColumn(GeneratedData data) {
        if (data.columns() != null) {
            for (ColumnDef c : data.columns()) {
                if (c.primaryKey()) return c.name();
            }
            for (ColumnDef c : data.columns()) {
                if ("id".equalsIgnoreCase(c.name())) return c.name();
            }
        }
        return null;
    }

    /** 目标列收口 scale：DECIMAL(M,D) 的 D，否则默认 2 */
    private static int targetScale(GeneratedData target, String column) {
        if (target.columns() != null) {
            for (ColumnDef c : target.columns()) {
                if (c.name().equals(column) && c.type() != null) {
                    Matcher m = DECIMAL_SCALE.matcher(c.type().trim());
                    if (m.find()) return Integer.parseInt(m.group(1));
                }
            }
        }
        return 2;
    }

    private static final Pattern DECIMAL_SCALE =
            Pattern.compile("(?i)decimal\\s*\\(\\s*\\d+\\s*,\\s*(\\d+)\\s*\\)");

    /** 值 → BigDecimal（null/非数值返回 null——该行计入缺失，不抛） */
    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
