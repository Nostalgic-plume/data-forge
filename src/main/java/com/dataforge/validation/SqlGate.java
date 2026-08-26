package com.dataforge.validation;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.stat.TableStat;
import com.dataforge.db.MysqlJdbcDataSource;
import com.dataforge.model.TableMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 自定义 SQL 结构闸门（CUSTOM_SQL 规则的安全阀）：对 LLM 产出的 check_sql（只读校验）
 * 与 fix_sql（修复 UPDATE）做 Druid 结构校验，把"LLM 自由 SQL"重新纳入受控边界。
 *
 * <p>双模式：
 * <ul>
 *   <li><b>check 模式</b>（只读）：必须 SELECT 单语句、禁危险函数/INTO OUTFILE/FOR UPDATE、
 *       强制表用 {@code {schema}} 占位符限定、表/列存在性对照、结果必须含目标表 id 列</li>
 *   <li><b>fix 模式</b>（修复）：必须 UPDATE 单表（表名 == 规则目标表）、必须 WHERE 引用目标表
 *       id（{@code {id}} 占位符，防全表 UPDATE）、禁 DELETE/DROP/INSERT/危险函数</li>
 * </ul>
 *
 * <p>信任模型延伸：闸门只保证<b>结构安全</b>，不保证语义正确——LLM 可能写语法合法但业务谓词
 * 错的 SQL；校验只读、修正 id 锚定 + 收敛控制兜底。占位符替换时机在解析前（否则 {@code {schema}}
 * 无法被 Druid 解析为合法表名）。
 */
public final class SqlGate {

    private SqlGate() {}

    /** 危险函数黑名单（SELECT/UPDATE 内均拦截）：读文件 / 拖慢 / 锁等待类 */
    private static final Set<String> DANGEROUS_FUNCTIONS = Set.of(
            "LOAD_FILE", "SLEEP", "BENCHMARK", "GET_LOCK", "RELEASE_LOCK",
            "IS_FREE_LOCK", "IS_USED_LOCK", "MASTER_POS_WAIT", "GTID_SUBTRACT");

    /** fix_sql 渲染产物：{schema} 已替换为 quote(staging)、{id} 已替换为 ? 参数占位符 */
    public record RenderedFix(String sql, int paramCount) {
    }

    /**
     * check_sql 编译：过只读闸门 + 替换 {schema} → 可执行 SQL。
     *
     * @throws IllegalStateException 闸门拒绝（中文原因列表拼接）
     */
    public static String compileCheckSql(String checkSql, String stagingSchema,
                                         TableMeta target, List<TableMeta> allTables) {
        List<String> errors = checkCheckSql(checkSql, stagingSchema, target, allTables);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("check_sql 未通过安全闸门：" + String.join("；", errors));
        }
        return replaceSchema(checkSql, stagingSchema);
    }

    /**
     * fix_sql 编译：过修复闸门 + 替换 {schema}、{id}→? → 可执行参数化 SQL。
     *
     * @throws IllegalStateException 闸门拒绝
     */
    public static RenderedFix compileFixSql(String fixSql, String stagingSchema, TableMeta target) {
        List<String> errors = checkFixSql(fixSql, stagingSchema, target);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("fix_sql 未通过安全闸门：" + String.join("；", errors));
        }
        String sql = replaceSchema(fixSql, stagingSchema).replace("{id}", "?");
        return new RenderedFix(sql, countOccurrences(fixSql, "{id}"));
    }

    /** check 模式失败原因列表（空 = 通过；供 planCustomSql 防御性重查，不抛异常） */
    public static List<String> checkCheckSql(String checkSql, String stagingSchema,
                                             TableMeta target, List<TableMeta> allTables) {
        List<String> errors = new ArrayList<>();
        if (isBlank(checkSql)) {
            errors.add("check_sql 为空");
            return errors;
        }
        baseChecks(checkSql, stagingSchema, false, errors);
        if (!errors.isEmpty()) return errors;
        List<SQLStatement> stmts = parseReplaced(checkSql, stagingSchema, false, errors);
        if (stmts == null) return errors;
        SQLStatement stmt = stmts.get(0);
        if (!(stmt instanceof SQLSelectStatement select)) {
            errors.add("check_sql 必须是只读 SELECT");
            return errors;
        }
        SQLSelectQueryBlock qb = select.getSelect() == null ? null : select.getSelect().getQueryBlock();
        if (qb != null && qb.getInto() != null) errors.add("禁止 SELECT INTO OUTFILE/DUMPFILE");
        if (qb != null && qb.isForUpdate()) errors.add("禁止 SELECT ... FOR UPDATE");
        if (matchesIntoOutfile(checkSql)) errors.add("禁止 SELECT INTO OUTFILE/DUMPFILE");  // 文本级兜底（Druid AST 解析差异）
        collectDangerFunctions(stmt, errors);
        if (!errors.isEmpty()) return errors;
        try {
            schemaScopeAndExistence(stmt, stagingSchema, allTables, errors);
        } catch (Exception e) {
            errors.add("表/列结构解析失败：" + e.getMessage());
        }
        if (errors.isEmpty() && target != null && !selectsTargetId(stmt, target.name())) {
            errors.add("check_sql 结果必须含目标表 `" + target.name() + "` 的 id 列（如 SELECT t.id AS id）");
        }
        return errors;
    }

    /** fix 模式失败原因列表（空 = 通过） */
    public static List<String> checkFixSql(String fixSql, String stagingSchema, TableMeta target) {
        List<String> errors = new ArrayList<>();
        if (isBlank(fixSql)) {
            errors.add("fix_sql 为空");
            return errors;
        }
        if (!fixSql.contains("{id}")) {
            errors.add("fix_sql 必须含 {id} 占位符（id 锚定，防全表 UPDATE）");
        }
        baseChecks(fixSql, stagingSchema, true, errors);
        if (!errors.isEmpty()) return errors;
        List<SQLStatement> stmts = parseReplaced(fixSql, stagingSchema, true, errors);
        if (stmts == null) return errors;
        SQLStatement stmt = stmts.get(0);
        if (!(stmt instanceof SQLUpdateStatement up)) {
            errors.add("fix_sql 必须是 UPDATE 语句（禁止 DELETE/DROP/INSERT）");
            return errors;
        }
        SQLTableSource ts = up.getTableSource();
        if (!(ts instanceof SQLExprTableSource ets)) {
            errors.add("fix_sql 禁止多表/JOIN/子查询作为更新目标");
        } else {
            String tableName = stripQuotes(ets.getTableName());
            if (target != null && !tableName.equals(target.name())) {
                errors.add("fix_sql 表名 `" + tableName + "` 必须等于规则目标表 `" + target.name() + "`");
            }
        }
        if (up.getWhere() == null) errors.add("禁止全表 UPDATE，fix_sql 必须带 WHERE 条件");
        collectDangerFunctions(stmt, errors);
        if (errors.isEmpty() && target != null && !whereUsesTargetId(stmt, target.name())) {
            errors.add("fix_sql 的 WHERE 必须引用目标表 `" + target.name() + "` 的 id 列（WHERE id = {id}）");
        }
        return errors;
    }

    // ---- 公共校验 ----

    /** 占位符契约：必须含 {schema}；替换后只允许保留 {id}（仅 fix 模式），其余 {占位符} 拒绝 */
    private static void baseChecks(String sql, String stagingSchema, boolean fixMode, List<String> errors) {
        if (!sql.contains("{schema}")) {
            errors.add("必须用 {schema} 占位符限定目标表（如 {schema}.orders）");
            return;
        }
        String replaced = replaceSchema(sql, stagingSchema);
        int idx = replaced.indexOf('{');
        while (idx >= 0) {
            int end = replaced.indexOf('}', idx);
            if (end < 0) {
                errors.add("存在未闭合的 { 占位符");
                return;
            }
            String inner = replaced.substring(idx + 1, end);
            if (!(fixMode && "id".equals(inner))) {
                errors.add("存在未识别的 {占位符}：{" + inner + "}（只允许 {schema}，修复 SQL 另允许 {id}）");
                return;
            }
            idx = replaced.indexOf('{', end + 1);
        }
    }

    /** 解析（{schema} 替换为 quote(staging)；fix 模式 {id} → ? 便于 Druid 解析参数占位符） */
    private static List<SQLStatement> parseReplaced(String sql, String stagingSchema,
                                                    boolean fixMode, List<String> errors) {
        String replaced = replaceSchema(sql, stagingSchema);
        if (fixMode) replaced = replaced.replace("{id}", "?");
        try {
            List<SQLStatement> stmts = SQLUtils.parseStatements(replaced, DbType.mysql);
            if (stmts == null || stmts.size() != 1) {
                errors.add("必须单条语句（禁止分号拼接多语句）");
                return null;
            }
            return stmts;
        } catch (Exception e) {
            errors.add("SQL 语法无法解析：" + e.getMessage());
            return null;
        }
    }

    /** 危险函数收集（MySqlASTVisitorAdapter 遍历全部函数调用，兼容 MySQL 方言 AST） */
    private static void collectDangerFunctions(SQLStatement stmt, List<String> errors) {
        List<String> hits = new ArrayList<>();
        stmt.accept(new MySqlASTVisitorAdapter() {
            @Override
            public boolean visit(SQLMethodInvokeExpr x) {
                String name = x.getMethodName();
                if (name != null && DANGEROUS_FUNCTIONS.contains(name.toUpperCase())) {
                    hits.add(name);
                }
                return true;
            }
        });
        if (!hits.isEmpty()) errors.add("禁止危险函数: " + String.join("/", hits));
    }

    /** schema 作用域 + 表/列存在性（对照 allTables；allTables 为 null 时跳过存在性，仍查作用域） */
    private static void schemaScopeAndExistence(SQLStatement stmt, String stagingSchema,
                                                List<TableMeta> allTables, List<String> errors) {
        Set<String> knownNames = allTables == null ? null
                : allTables.stream().map(TableMeta::name).collect(Collectors.toSet());
        // 表引用收集：遍历 SQLExprTableSource（覆盖 FROM/JOIN/子查询源；别名解析可靠，兼容 MySQL 方言）
        List<String> tableRefs = new ArrayList<>();
        stmt.accept(new MySqlASTVisitorAdapter() {
            @Override
            public boolean visit(SQLExprTableSource x) {
                tableRefs.add(stripQuotes(x.getTableName()));
                return true;
            }
        });
        if (tableRefs.isEmpty()) {
            errors.add("未解析到任何表引用");
            return;
        }
        for (String ref : tableRefs) {
            if (knownNames != null && !knownNames.contains(ref)) {
                errors.add("表 `" + ref + "` 不存在于表结构");
            }
        }
        // schema 作用域：SchemaStatVisitor 表名 "schema.table" 或 "table"，schema 段必须 == stagingSchema
        SchemaStatVisitor visitor = new SchemaStatVisitor();
        stmt.accept(visitor);
        for (TableStat.Name name : visitor.getTables().keySet()) {
            String full = normalizeTable(name.getName());
            String[] parts = full.split("\\.");
            String schema = parts.length > 1 ? parts[0] : null;
            if (schema == null || !schema.equalsIgnoreCase(stagingSchema)) {
                errors.add("表 `" + full + "` 必须用 {schema} 限定（禁止裸表名/跨库引用）");
            }
        }
        // 列存在性：对能归一到已知表的列校验（列名剥 schema 前缀）
        if (knownNames != null) {
            for (TableStat.Column col : visitor.getColumns()) {
                String table = bareTable(col.getTable());
                if (knownNames.contains(table) && !columnExists(table, col.getName(), allTables)) {
                    errors.add("列 `" + table + "." + col.getName() + "` 不存在于表结构");
                }
            }
        }
    }

    private static boolean selectsTargetId(SQLStatement stmt, String targetTable) {
        SchemaStatVisitor visitor = new SchemaStatVisitor();
        stmt.accept(visitor);
        for (TableStat.Column col : visitor.getColumns()) {
            if (tableMatches(col.getTable(), targetTable)
                    && "id".equalsIgnoreCase(col.getName()) && col.isSelect()) {
                return true;
            }
        }
        return false;
    }

    private static boolean whereUsesTargetId(SQLStatement stmt, String targetTable) {
        // Druid 对 UPDATE 的 WHERE 列不标 isWhere（见调试）→ AST 遍历找 id 列引用；
        // fix_sql 已强制单表 + 表名 == target + 含 {id} 占位符，SQL 里出现 id 即 WHERE id 锚定
        boolean[] foundId = {false};
        stmt.accept(new MySqlASTVisitorAdapter() {
            @Override
            public boolean visit(SQLIdentifierExpr x) {
                if ("id".equalsIgnoreCase(x.getName())) foundId[0] = true;
                return true;
            }

            @Override
            public boolean visit(SQLPropertyExpr x) {
                if ("id".equalsIgnoreCase(x.getName())) foundId[0] = true;
                return true;
            }
        });
        return foundId[0];
    }

    private static boolean columnExists(String table, String column, List<TableMeta> allTables) {
        for (TableMeta t : allTables) {
            if (t.name().equals(table)) {
                return t.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(column));
            }
        }
        return false;
    }

    // ---- 工具 ----

    private static String replaceSchema(String sql, String stagingSchema) {
        return sql.replace("{schema}", MysqlJdbcDataSource.quote(stagingSchema));
    }

    private static String normalizeTable(String table) {
        return stripQuotes(table == null ? "" : table);
    }

    /** 文本级 INTO OUTFILE/DUMPFILE 兜底（Druid AST getInto() 对不同写法解析有差异） */
    private static boolean matchesIntoOutfile(String sql) {
        return sql != null && sql.toUpperCase().matches("(?s).*\\bINTO\\s+(OUTFILE|DUMPFILE)\\b.*");
    }

    /** 剥 schema 前缀取裸表名（"shop_staging.orders" → "orders"） */
    private static String bareTable(String table) {
        String t = normalizeTable(table);
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    /** 表名宽容匹配（Druid 可能记录 "orders" 或 "schema.orders" 或反引号变体） */
    private static boolean tableMatches(String actual, String target) {
        String t = normalizeTable(actual);
        return t.equals(target) || t.endsWith("." + target);
    }

    private static String stripQuotes(String name) {
        return name == null ? "" : name.replace("`", "");
    }

    private static int countOccurrences(String s, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
