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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用规则回填器（derive-universal-design.md §H7/§3.5）：生成侧消费 {@code AGG_EQUAL} 与
 * {@code EXPR_EQUAL} 修正规则，在内存数据上做回填。一份规则两处复用：本引擎在<b>生成后</b>回填，
 * 修正侧 SqlValidator/CorrectionEngine 在 staging 复核。表名/列名/表达式全部来自规则 params，
 * 引擎不持有业务知识（§H1 无语义）。
 *
 * <p><b>两类规则分两遍执行</b>（不依赖规则在文件中的排列顺序，顺序由语义决定）：
 * <ol>
 *   <li><b>AGG_EQUAL</b>：{@code target.column = SUM(ref_table.sum_col GROUP BY ref_table.join_col 对 target.id)}，
 *       无明细匹配的 target 行填 0（没有明细的订单，总额本来就是 0）</li>
 *   <li><b>EXPR_EQUAL</b>：{@code target.column = expr(同行其它列)}——<b>必须在 AGG 之后</b>，
 *       因为表达式常引用 AGG 刚回填出来的列（如 {@code actual_amount = total_amount - discount_amount + freight}）</li>
 * </ol></p>
 *
 * <p>显式失败（§H4）：AGG 规则的 ref/target 表不在 data 内 → warn 跳过；EXPR 规则引用列不存在、
 * 或行值无法参与求值 → 抛（配置错误/数据缺口必须暴露，不静默留 0 充数）。
 * 覆盖值血缘 CORRECTED，按目标列 DECIMAL scale 收口。</p>
 */
public final class ReconcileEngine {

    private static final Logger log = LoggerFactory.getLogger(ReconcileEngine.class);

    private ReconcileEngine() {}

    /**
     * 对内存数据执行规则回填：第一遍 AGG_EQUAL，第二遍 EXPR_EQUAL。
     *
     * @param data     已生成的各表数据（tableName → GeneratedData）
     * @param aggRules 修正规则（<b>可原样传入全部 correctionRules</b>：本引擎按 type 自行筛选，
     *                 不认识的类型静默跳过——调用侧不需要、也不应该先筛一遍）
     */
    public static void apply(Map<String, GeneratedData> data, List<ProjectRules.Rule> aggRules) {
        if (data == null || data.isEmpty() || aggRules == null || aggRules.isEmpty()) {
            return;
        }
        // 第一遍：跨表聚合回填（EXPR_EQUAL 可能引用这里刚回填出来的列，故必须在前）
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
            fill(target, rule.column(), ref, sumCol, joinCol);
        }
        // 第二遍：行内表达式回填（依赖第一遍结果，顺序不可交换）
        for (ProjectRules.Rule rule : aggRules) {
            if (rule == null || !"EXPR_EQUAL".equals(rule.type())) continue;
            if (rule.table() == null || rule.column() == null) continue;
            GeneratedData target = data.get(rule.table());
            if (target == null) {
                log.info("行内表达式回填跳过 {}:{}：目标表不在生成集", rule.table(), rule.column());
                continue;
            }
            fillExpr(target, rule);
        }
    }

    /**
     * 行内表达式回填：{@code target.column = expr(同行其它列)}，逐行求值覆盖。
     *
     * <p>护栏：引用列必须存在于目标表（违者抛——配置错误必须暴露，不静默掠过）；
     * 行值无法转数值同样抛（宁可失败，不可静默留 0 充数）。</p>
     */
    private static void fillExpr(GeneratedData target, ProjectRules.Rule rule) {
        String expr = rule.params() == null ? null : rule.params().get("expr");
        if (expr == null || expr.isBlank()) {
            log.warn("行内表达式回填跳过 {}:{}：规则缺 params.expr", rule.table(), rule.column());
            return;
        }
        if (target.rows() == null || target.rows().isEmpty()) return;
        Set<String> refs = ExpressionEvaluator.referencedColumns(expr);
        Set<String> tableCols = new HashSet<>();
        if (target.columns() != null) {
            for (ColumnDef c : target.columns()) tableCols.add(c.name());
        }
        Set<String> missing = new HashSet<>(refs);
        missing.removeAll(tableCols);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("行内表达式回填 " + target.tableName() + "." + rule.column()
                    + " 引用了不存在的列 " + missing + "（expr: " + expr + "）");
        }
        int scale = targetScale(target, rule.column());
        for (DataRow row : target.rows()) {
            Map<String, BigDecimal> rowValues = new HashMap<>();
            for (String col : refs) {
                rowValues.put(col, toBigDecimal(row.values().get(col)));
            }
            BigDecimal value;
            try {
                value = ExpressionEvaluator.eval(expr, rowValues);
            } catch (ExpressionException e) {
                throw new IllegalStateException("行内表达式回填 " + target.tableName() + "." + rule.column()
                        + " 求值失败（expr: " + expr + "）: " + e.getMessage(), e);
            }
            row.values().put(rule.column(), value.setScale(scale, RoundingMode.HALF_UP));
            row.lineage().put(rule.column(), SourceTag.CORRECTED);
        }
        log.info("行内表达式回填 {}:{} = {}（{} 行）",
                target.tableName(), rule.column(), expr, target.rows().size());
    }

    /** 单条规则：ref 按 join_col 分组求和 → 回填 target 每行（命中组=sum，无组=0） */
    private static void fill(GeneratedData target, String targetColumn,
                             GeneratedData ref, String sumCol, String joinCol) {
        if (target.rows().isEmpty() || ref.rows().isEmpty()) return;
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
            return;
        }
        int scale = targetScale(target, targetColumn);
        for (DataRow row : target.rows()) {
            Object idVal = row.values().get(idCol);
            if (idVal == null) continue;
            BigDecimal total = sums.get(idVal.toString());
            BigDecimal value = (total == null ? BigDecimal.ZERO : total).setScale(scale, RoundingMode.HALF_UP);
            row.values().put(targetColumn, value);
            row.lineage().put(targetColumn, SourceTag.CORRECTED);
        }
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
