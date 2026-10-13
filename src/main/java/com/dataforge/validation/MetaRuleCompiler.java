package com.dataforge.validation;

import com.dataforge.db.MysqlJdbcDataSource;
import com.dataforge.derivation.ExpressionEvaluator;
import com.dataforge.derivation.ExpressionException;
import com.dataforge.model.ForeignKeyDef;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 元数据规则编译器：从 DDL 自动推导通用检查 SQL + 金额项目规则编译。
 *
 * <p>SQL 全部为受控模板（反引号包裹标识符），非 LLM 自由拼接。
 * 每个 FK/唯一键生成一条扫描 SQL，结果行含 id（供 id 锚定修正）。</p>
 */
@Component
public class MetaRuleCompiler implements RuleCompiler {

    @Override
    public String compileMetaCheck(TableMeta table, MetaCheckType type, String stagingSchema) {
        String staging = MysqlJdbcDataSource.quote(stagingSchema);
        String tableQ = MysqlJdbcDataSource.quote(table.name());
        return switch (type) {
            case FK_ORPHAN -> compileFkOrphan(table, staging, tableQ);
            case UNIQUE_CONFLICT -> compileUniqueConflict(table, staging, tableQ);
        };
    }

    /** FK 悬挂：子表 FK 左连接父表，父表主键为 NULL 即为悬挂 */
    private String compileFkOrphan(TableMeta table, String staging, String tableQ) {
        StringBuilder sb = new StringBuilder();
        for (ForeignKeyDef fk : table.foreignKeys()) {
            String childCol = MysqlJdbcDataSource.quote(fk.column());
            String parentTable = MysqlJdbcDataSource.quote(fk.refTable());
            String parentCol = MysqlJdbcDataSource.quote(fk.refColumn());
            sb.append("SELECT c.`id` AS `id`, c.").append(childCol)
                    .append(" AS `fk_value`, '").append(fk.column()).append("' AS `fk_col`\n")
                    .append("FROM ").append(staging).append('.').append(tableQ).append(" c\n")
                    .append("LEFT JOIN ").append(staging).append('.').append(parentTable)
                    .append(" p ON c.").append(childCol).append(" = p.").append(parentCol).append("\n")
                    .append("WHERE p.").append(parentCol).append(" IS NULL\n")
                    .append("UNION ALL\n");
        }
        if (sb.length() == 0) return "SELECT NULL AS `id`, NULL AS `fk_value`, NULL AS `fk_col` WHERE 1=0";
        sb.setLength(sb.length() - "UNION ALL\n".length());
        return sb.toString();
    }

    /** 唯一键冲突：按唯一列组 GROUP BY，出现 >1 次即冲突。多组列数不一致时补 NULL 对齐（UNION 各段列数必须一致） */
    private String compileUniqueConflict(TableMeta table, String staging, String tableQ) {
        var groups = table.uniqueKeys().stream()
                .map(g -> g.split(","))
                .map(a -> Arrays.stream(a).map(String::trim).toArray(String[]::new))
                .toList();
        if (groups.isEmpty()) return "SELECT NULL AS `cnt` WHERE 1=0";
        int maxCols = groups.stream().mapToInt(a -> a.length).max().orElse(1);
        StringBuilder sb = new StringBuilder();
        for (String[] group : groups) {
            String cols = Arrays.stream(group).map(MysqlJdbcDataSource::quote)
                    .collect(Collectors.joining(", "));
            StringBuilder padded = new StringBuilder(cols);
            for (int i = group.length; i < maxCols; i++) {
                padded.append(", NULL");
            }
            sb.append("SELECT ").append(padded).append(", COUNT(*) AS `cnt`\n")
                    .append("FROM ").append(staging).append('.').append(tableQ).append("\n")
                    .append("GROUP BY ").append(cols).append("\n")
                    .append("HAVING COUNT(*) > 1\n")
                    .append("UNION ALL\n");
        }
        sb.setLength(sb.length() - "UNION ALL\n".length());
        return sb.toString();
    }

    /**
     * 项目规则编译（P4 通用化：引擎按 type 编译，业务参数全部来自 rule.params，不写死表名）。
     * AGG_EQUAL：target.column = SUM(ref_table.sum_col GROUP BY ref_table.join_col 对 target.id)。
     * EXPR_EQUAL：target.column = expr(同行其它列)（表达式为白名单四则运算）。
     * CUSTOM_SQL：LLM 产出校验 SQL，过结构闸门（SqlGate）后作为检查 SQL（tables 可为 null）。
     */
    @Override
    public String compileProjectRule(ProjectRules.Rule rule, List<TableMeta> tables, String stagingSchema) {
        TableMeta table = tables == null ? null : tables.stream()
                .filter(t -> t.name().equals(rule.table())).findFirst().orElse(null);
        String staging = MysqlJdbcDataSource.quote(stagingSchema);
        validateParams(rule, table);   // 编译前参数结构校验（错参数拒绝 + 上报，不静默）
        return switch (rule.type()) {
            case "AGG_EQUAL" -> compileAggEqual(rule, staging);
            case "EXPR_EQUAL" -> compileExprEqual(rule, staging);
            case "VALUE_RANGE" -> compileValueRange(rule, staging);
            case "NON_NULL" -> compileNonNull(rule, staging);
            case "FORMAT" -> compileFormat(rule, staging);
            case "STATUS_FLOW" -> compileStatusFlow(rule, staging);
            case "CUSTOM_SQL" -> compileCustomSql(rule, table, tables, stagingSchema);
            default -> throw new UnsupportedOperationException(
                    "暂不支持该项目规则类型: " + rule.type() + " / " + rule.expression());
        };
    }

    /**
     * 参数结构校验（编译前，错参数拒绝 + 上报，不静默）：表/列存在性、范围合法性、regex/flow 存在。
     * table 为 null 时（Web 试编译路径）跳过结构校验，只做参数存在性/合法性。
     */
    private void validateParams(ProjectRules.Rule rule, TableMeta table) {
        if (rule == null || rule.params() == null) return;
        if (table != null) {
            boolean colExists = table.columns().stream().anyMatch(c -> c.name().equals(rule.column()));
            if (!colExists) {
                throw new IllegalStateException("规则列 `" + rule.column() + "` 不存在于表 `" + rule.table() + "`");
            }
        }
        switch (rule.type()) {
            case "VALUE_RANGE" -> {
                String min = rule.params().get("min");
                String max = rule.params().get("max");
                if ((min == null || min.isBlank()) && (max == null || max.isBlank())) {
                    throw new IllegalStateException("VALUE_RANGE 规则缺少参数 min/max");
                }
                Double minV = parseDouble(min);
                Double maxV = parseDouble(max);
                if (minV != null && maxV != null && minV > maxV) {
                    throw new IllegalStateException("VALUE_RANGE min(" + min + ") > max(" + max + ")");
                }
            }
            case "EXPR_EQUAL" -> {
                String expr = rule.params().get("expr");
                if (expr == null || expr.isBlank()) {
                    throw new IllegalStateException("EXPR_EQUAL 规则缺少参数 expr");
                }
                // 先过字符集白名单（非法字符统一在此拒绝），再做语法解析——顺序不可交换：
                // 反过来的话 `a; DROP TABLE b` 会先撞解析器，错误类型/信息都不是护栏该给的
                toSqlExpression(expr, rule);
                if (table != null) {
                    for (String c : ExpressionEvaluator.referencedColumns(expr)) {
                        if (table.columns().stream().noneMatch(x -> x.name().equals(c))) {
                            throw new IllegalStateException("EXPR_EQUAL 表达式引用列 `" + c
                                    + "` 不存在于表 `" + rule.table() + "`");
                        }
                    }
                }
            }
            case "FORMAT" -> {
                String regex = rule.params().get("regex");
                if (regex == null || regex.isBlank()) {
                    throw new IllegalStateException("FORMAT 规则缺少参数 regex");
                }
            }
            case "STATUS_FLOW" -> {
                String flow = rule.params().get("flow");
                if (flow == null || flow.isBlank()) {
                    throw new IllegalStateException("STATUS_FLOW 规则缺少参数 flow");
                }
                if (table != null) {
                    for (String c : flow.split(",")) {
                        String col = c.trim();
                        if (table.columns().stream().noneMatch(x -> x.name().equals(col))) {
                            throw new IllegalStateException(
                                    "STATUS_FLOW 时间列 `" + col + "` 不存在于表 `" + rule.table() + "`");
                        }
                    }
                }
            }
            case "CUSTOM_SQL" -> {
                String checkSql = rule.params().get("check_sql");
                if (checkSql == null || checkSql.isBlank()) {
                    throw new IllegalStateException("CUSTOM_SQL 规则缺少参数 check_sql");
                }
                if (table != null) {
                    String fixCol = rule.params().getOrDefault("fix_column", rule.column());
                    if (table.columns().stream().noneMatch(c -> c.name().equals(fixCol))) {
                        throw new IllegalStateException(
                                "CUSTOM_SQL 修正列 `" + fixCol + "` 不存在于表 `" + rule.table() + "`");
                    }
                }
            }
            default -> { }
        }
    }

    /** AGG_EQUAL 检查 SQL：目标列 ≠ 引用表按 join 键求和 → 差异行（含 id，喂修正 agent） */
    private String compileAggEqual(ProjectRules.Rule rule, String staging) {
        String refTable = MysqlJdbcDataSource.quote(param(rule, "ref_table"));
        String sumCol = MysqlJdbcDataSource.quote(param(rule, "sum_col"));
        String joinCol = MysqlJdbcDataSource.quote(param(rule, "join_col"));
        String targetTable = MysqlJdbcDataSource.quote(rule.table());
        String targetCol = MysqlJdbcDataSource.quote(rule.column());
        return "SELECT t.`id` AS `id`, t." + targetCol + " AS `actual`,\n"
                + "       COALESCE(e.`expected`, 0) AS `expected`\n"
                + "FROM " + staging + "." + targetTable + " t\n"
                + "LEFT JOIN (SELECT " + joinCol + ", SUM(" + sumCol + ") AS `expected` FROM "
                + staging + "." + refTable + " GROUP BY " + joinCol + ") e\n"
                + "       ON t.`id` = e." + joinCol + "\n"
                + "WHERE ABS(t." + targetCol + " - COALESCE(e.`expected`, 0)) > 0.01";
    }

    /**
     * EXPR_EQUAL 校验 SQL：目标列 ≠ 同行表达式求值 → 差异行（含 id 与 expected，喂修正 agent）。
     *
     * <p>表达式与生成侧 {@link ExpressionEvaluator} 同语法（数字/列引用/四则运算/括号/round），
     * 经 {@link #toSqlExpression} 做字符集白名单 + 标识符反引号化后才拼入 SQL——
     * 非白名单字符一律编译期拒绝，不做"拼接后放行"。</p>
     */
    private String compileExprEqual(ProjectRules.Rule rule, String staging) {
        String sqlExpr = toSqlExpression(param(rule, "expr"), rule);
        String target = staging + "." + MysqlJdbcDataSource.quote(rule.table());
        String col = MysqlJdbcDataSource.quote(rule.column());
        return "SELECT t.`id` AS `id`, t." + col + " AS `actual`,\n"
                + "       (" + sqlExpr + ") AS `expected`\n"
                + "FROM " + target + " t\n"
                + "WHERE ABS(t." + col + " - (" + sqlExpr + ")) > 0.01";
    }

    /**
     * EXPR_EQUAL 表达式允许的字符集（列名/数字/四则运算/括号/逗号——逗号为 round(x,n) 预留）。
     *
     * <p>用 {@code \p{L}\p{N}} 而非 ASCII：生成侧 {@link ExpressionEvaluator} 的解析器以
     * {@code Character.isLetter/isDigit} 判标识符（Unicode 全量），此处若只放行 ASCII 会造成
     * 「生成侧能用、校验侧被拒」的能力割裂。</p>
     */
    private static final Pattern EXPR_ALLOWED =
            Pattern.compile("^[\\p{L}\\p{N}_+\\-*/().,\\s]+$");

    /**
     * 表达式中的标识符（Unicode 字母/下划线开头，lookbehind 防从数字中间截断）。
     * <b>必须与 {@link #EXPR_ALLOWED} 同口径放宽</b>——否则 Unicode 列名通过白名单却匹配不到，
     * 会不被反引号包裹就拼进 SQL。
     */
    private static final Pattern EXPR_IDENT =
            Pattern.compile("(?<![\\p{L}\\p{N}_])[\\p{L}_][\\p{L}\\p{N}_]*");

    /** 表达式内允许原样保留的 SQL 函数名（不加反引号；MySQL 不允许反引号包裹函数名） */
    private static final Set<String> EXPR_FUNCS = Set.of("round");

    /**
     * 表达式 → SQL 表达式：字符集白名单 + 语法校验（复用生成侧解析器）+ 标识符反引号化。
     * 出现白名单外的标识符（既非列引用也非允许函数）→ 抛，不做静默透传。
     */
    private static String toSqlExpression(String expr, ProjectRules.Rule rule) {
        if (!EXPR_ALLOWED.matcher(expr).matches()) {
            throw new IllegalStateException("EXPR_EQUAL 表达式含非法字符（仅允许列名/数字/+-*/()）: " + expr);
        }
        Set<String> refs;
        try {
            refs = ExpressionEvaluator.referencedColumns(expr);   // 语法校验：非法语法在此抛
        } catch (ExpressionException e) {
            throw new IllegalStateException("EXPR_EQUAL 表达式语法错误（规则 " + rule.table() + "."
                    + rule.column() + "）: " + e.getMessage(), e);
        }
        Matcher m = EXPR_IDENT.matcher(expr);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String id = m.group();
            String replacement;
            if (EXPR_FUNCS.contains(id.toLowerCase())) {
                replacement = id;
            } else if (refs.contains(id)) {
                replacement = "`" + id + "`";
            } else {
                throw new IllegalStateException("EXPR_EQUAL 表达式含未声明标识符 `" + id
                        + "`（规则 " + rule.table() + "." + rule.column() + "）");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** VALUE_RANGE 校验 SQL：列超出 [min,max] 即问题行（单侧边界也支持） */
    private String compileValueRange(ProjectRules.Rule rule, String staging) {
        String min = rule.params().get("min");
        String max = rule.params().get("max");
        String target = staging + "." + MysqlJdbcDataSource.quote(rule.table());
        String col = MysqlJdbcDataSource.quote(rule.column());
        StringBuilder where = new StringBuilder();
        if (min != null && !min.isBlank()) where.append("t.").append(col).append(" < ").append(min);
        if (max != null && !max.isBlank()) {
            if (where.length() > 0) where.append(" OR ");
            where.append("t.").append(col).append(" > ").append(max);
        }
        return "SELECT t.`id` AS `id` FROM " + target + " t WHERE " + where;
    }

    /** NON_NULL 校验 SQL：NOT NULL 列存在 NULL 值即问题行 */
    private String compileNonNull(ProjectRules.Rule rule, String staging) {
        return "SELECT `id` FROM " + staging + "." + MysqlJdbcDataSource.quote(rule.table())
                + " WHERE `" + rule.column() + "` IS NULL";
    }

    /** FORMAT 校验 SQL：列值不匹配正则即问题行（regex 来自 params，SQL 字符串转义） */
    private String compileFormat(ProjectRules.Rule rule, String staging) {
        String regex = rule.params().get("regex");
        return "SELECT `id` FROM " + staging + "." + MysqlJdbcDataSource.quote(rule.table())
                + " WHERE `" + rule.column() + "` IS NOT NULL AND `" + rule.column()
                + "` NOT REGEXP '" + regex.replace("'", "''") + "'";
    }

    /**
     * STATUS_FLOW 校验 SQL：触发状态下时间列严格递增（col0 < col1 < ...）。
     * 问题 = 任一时间列为空 或 相邻列非严格递增；未声明 trigger_status 时只校验时间线。
     */
    private String compileStatusFlow(ProjectRules.Rule rule, String staging) {
        String[] cols = rule.params().get("flow").split(",");
        if (cols.length < 2) {
            throw new IllegalStateException("STATUS_FLOW 至少需要两个时间列（flow）");
        }
        StringBuilder conditions = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            String c = cols[i].trim();
            if (i > 0) conditions.append(" OR ");
            conditions.append("`").append(c).append("` IS NULL");
            if (i < cols.length - 1) {
                conditions.append(" OR `").append(c).append("` >= `").append(cols[i + 1].trim()).append("`");
            }
        }
        String where = "(" + conditions + ")";
        String trigger = rule.params().get("trigger_status");
        if (trigger != null && !trigger.isBlank()) {
            String triggerCol = rule.params().get("trigger_status_col");
            String tc = (triggerCol == null || triggerCol.isBlank()) ? "status" : triggerCol;
            where = "`" + tc + "` = " + trigger + " AND " + where;
        }
        return "SELECT `id` FROM " + staging + "." + MysqlJdbcDataSource.quote(rule.table()) + " WHERE " + where;
    }

    /**
     * CUSTOM_SQL 编译：LLM 产出的校验 SQL 过结构闸门（SqlGate）后作为检查 SQL。
     * 表名用 {@code {schema}} 占位符，编译时替换为 quote(staging)（防裸表名/跨库越界）。
     * fix_sql（修复 UPDATE）同过闸门但由修正侧消费——此处编译期拒绝坏 fix（Web 试编译可见）。
     */
    private String compileCustomSql(ProjectRules.Rule rule, TableMeta table,
                                    List<TableMeta> tables, String stagingSchema) {
        String checkSql = rule.params().get("check_sql");
        if (checkSql == null || checkSql.isBlank()) {
            throw new IllegalStateException("CUSTOM_SQL 规则缺少参数 check_sql");
        }
        String compiled = SqlGate.compileCheckSql(checkSql, stagingSchema, table, tables);
        String fixSql = rule.params().get("fix_sql");
        if (fixSql != null && !fixSql.isBlank()) {
            SqlGate.compileFixSql(fixSql, stagingSchema, table);
        }
        return compiled;
    }

    private static Double parseDouble(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String param(ProjectRules.Rule rule, String key) {
        String value = rule.params().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "AGG_EQUAL 规则缺少参数 " + key + "（表 " + rule.table() + "." + rule.column() + "）");
        }
        return value;
    }
}
