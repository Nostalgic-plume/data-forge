package com.dataforge.correction;

import com.dataforge.db.MysqlJdbcDataSource;

/**
 * 受控修正 SQL 模板（P3 修正 SQL 化的"规则通道"）：预定义模板 + 参数，
 * 非 LLM 自由拼接。标识符全部反引号包裹，scopeMinId 为数字（拼接安全）。
 *
 * <p>每个模板成对：check（取受影响行 + 修正目标值）→ apply（批量修正），
 * 对应"check → apply → check"循环的第一环。结果行含 id，供 id 锚定生成 FixCommand。</p>
 */
final class CorrectionSqls {

    private CorrectionSqls() {}

    /**
     * AGG_EQUAL check（P4 通用化，参数来自规则）：目标列 ≠ 引用表按 join 键求和的差异行。
     * 返回 {@code {id, current, expected}}，expected = 修正后的正确值。
     */
    static String aggEqualCheckSql(String staging, String targetTable, String targetCol,
                                   String refTable, String sumCol, String joinCol, Long scopeMinId) {
        return "SELECT t.`id` AS `id`, t." + q(targetCol) + " AS `current`,\n"
                + "       COALESCE(e.`expected`, 0) AS `expected`\n"
                + "FROM " + q(staging) + "." + q(targetTable) + " t\n"
                + "LEFT JOIN (SELECT " + q(joinCol) + ", SUM(" + q(sumCol) + ") AS `expected` FROM "
                + q(staging) + "." + q(refTable) + " GROUP BY " + q(joinCol) + ") e\n"
                + "  ON t.`id` = e." + q(joinCol) + "\n"
                + "WHERE ABS(t." + q(targetCol) + " - COALESCE(e.`expected`, 0)) > 0.01"
                + scopeClause("t", scopeMinId);
    }

    /** AGG_EQUAL apply：把差异行目标列修正为聚合值 */
    static String aggEqualApplySql(String staging, String targetTable, String targetCol,
                                   String refTable, String sumCol, String joinCol, Long scopeMinId) {
        return "UPDATE " + q(staging) + "." + q(targetTable) + " t\n"
                + "LEFT JOIN (SELECT " + q(joinCol) + ", SUM(" + q(sumCol) + ") AS `expected` FROM "
                + q(staging) + "." + q(refTable) + " GROUP BY " + q(joinCol) + ") e\n"
                + "  ON t.`id` = e." + q(joinCol) + "\n"
                + "SET t." + q(targetCol) + " = COALESCE(e.`expected`, 0)\n"
                + "WHERE ABS(t." + q(targetCol) + " - COALESCE(e.`expected`, 0)) > 0.01"
                + scopeClause("t", scopeMinId);
    }

    /**
     * 唯一后缀去重 check：重复组内除保留行外的问题行 + 当前值。
     * 返回 {@code {id, value}}（保留行 = 每组 MIN(id)，其余追加 "_<id>" 后缀）。
     */
    static String uniqueCheckSql(String staging, String table, String col, Long scopeMinId) {
        return "SELECT `id`, `col` AS `value` FROM (\n"
                + "  SELECT `id`, " + q(col) + " AS `col`,\n"
                + "         ROW_NUMBER() OVER (PARTITION BY " + q(col) + " ORDER BY `id`) AS rn\n"
                + "  FROM " + q(staging) + "." + q(table)
                + (scopeMinId != null ? " WHERE `id` > " + scopeMinId : "") + "\n"
                + ") x WHERE x.rn > 1";
    }

    /** 唯一后缀去重 apply：重复行追加 "_<id>" 后缀 */
    static String uniqueApplySql(String staging, String table, String col, Long scopeMinId) {
        return "UPDATE " + q(staging) + "." + q(table) + " u\n"
                + "JOIN (SELECT `id`, " + q(col) + " AS `col`,\n"
                + "             ROW_NUMBER() OVER (PARTITION BY " + q(col) + " ORDER BY `id`) AS rn\n"
                + "       FROM " + q(staging) + "." + q(table)
                + (scopeMinId != null ? " WHERE `id` > " + scopeMinId : "") + ") x ON u.`id` = x.`id`\n"
                + "SET u." + q(col) + " = CONCAT(x.`col`, '_', u.`id`)\n"
                + "WHERE x.rn > 1";
    }

    /**
     * FK 悬挂 check：子表 FK 引用父表不存在的问题行。
     * 返回 {@code {id, fk_col}}。
     */
    static String fkOrphanSql(String staging, String child, String fkCol,
                              String parent, String refCol, Long scopeMinId) {
        return "SELECT c.`id` AS `id`, c." + q(fkCol) + " AS `fk_value`\n"
                + "FROM " + q(staging) + "." + q(child) + " c\n"
                + "LEFT JOIN " + q(staging) + "." + q(parent) + " p ON c." + q(fkCol)
                + " = p." + q(refCol) + "\n"
                + "WHERE p." + q(refCol) + " IS NULL AND c." + q(fkCol) + " IS NOT NULL"
                + scopeClause("c", scopeMinId);
    }

    /** 父表 PK 池（FK 重分配的目标池） */
    static String fkPoolSql(String staging, String parent, String refCol) {
        return "SELECT DISTINCT " + q(refCol) + " AS `pk` FROM " + q(staging) + "." + q(parent)
                + " WHERE " + q(refCol) + " IS NOT NULL";
    }

    /** VALUE_RANGE check：col 超出 [min,max] 的问题行（含 id + 当前值，单侧边界也支持） */
    static String valueRangeCheckSql(String staging, String table, String col, String min, String max, Long scopeMinId) {
        StringBuilder where = new StringBuilder();
        if (min != null && !min.isBlank()) where.append("t.").append(q(col)).append(" < ").append(min);
        if (max != null && !max.isBlank()) {
            if (where.length() > 0) where.append(" OR ");
            where.append("t.").append(q(col)).append(" > ").append(max);
        }
        return "SELECT t.`id` AS `id`, t." + q(col) + " AS `value` FROM " + q(staging) + "." + q(table)
                + " t WHERE " + where + scopeClause("t", scopeMinId);
    }

    /** VALUE_RANGE apply：范围钳制到边界（LEAST/GREATEST，单侧/双侧自适应） */
    static String valueRangeApplySql(String staging, String table, String col, String min, String max, Long scopeMinId) {
        StringBuilder where = new StringBuilder();
        if (min != null && !min.isBlank()) where.append("t.").append(q(col)).append(" < ").append(min);
        if (max != null && !max.isBlank()) {
            if (where.length() > 0) where.append(" OR ");
            where.append("t.").append(q(col)).append(" > ").append(max);
        }
        return "UPDATE " + q(staging) + "." + q(table) + " t SET t." + q(col) + " = " + clampExpr(q(col), min, max)
                + " WHERE " + where + scopeClause("t", scopeMinId);
    }

    /** NON_NULL check：NOT NULL 列存在 NULL 值的问题行 */
    static String nonNullCheckSql(String staging, String table, String col, Long scopeMinId) {
        return "SELECT t.`id` AS `id` FROM " + q(staging) + "." + q(table)
                + " t WHERE t." + q(col) + " IS NULL" + scopeClause("t", scopeMinId);
    }

    /** NON_NULL apply：补默认值（defaultVal 为 SQL 字面量，如 0 / 'N/A'） */
    static String nonNullApplySql(String staging, String table, String col, String defaultVal, Long scopeMinId) {
        return "UPDATE " + q(staging) + "." + q(table) + " t SET t." + q(col) + " = " + defaultVal
                + " WHERE t." + q(col) + " IS NULL" + scopeClause("t", scopeMinId);
    }

    /** FORMAT check：列值不匹配正则的问题行（重生成走内存，apply 由 CorrectionEngine 处理） */
    static String formatCheckSql(String staging, String table, String col, String regex, Long scopeMinId) {
        return "SELECT t.`id` AS `id` FROM " + q(staging) + "." + q(table)
                + " t WHERE t." + q(col) + " IS NOT NULL AND t." + q(col)
                + " NOT REGEXP '" + regex.replace("'", "''") + "'" + scopeClause("t", scopeMinId);
    }

    /** STATUS_FLOW check：触发状态下时间列严格递增（col0 < col1 < ...）违规行；apply 无（保守上报残留） */
    static String statusFlowCheckSql(String staging, String table, String flow, String triggerStatus,
                                     String triggerCol, Long scopeMinId) {
        String[] cols = flow.split(",");
        StringBuilder conditions = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            String c = cols[i].trim();
            conditions.append("t.").append(q(c)).append(" IS NULL");
            if (i < cols.length - 1) {
                conditions.append(" OR t.").append(q(c)).append(" >= t.").append(q(cols[i + 1].trim()));
            }
        }
        String where = "(" + conditions + ")";
        if (triggerStatus != null && !triggerStatus.isBlank()) {
            String tc = (triggerCol == null || triggerCol.isBlank()) ? "status" : triggerCol;
            where = "t." + q(tc) + " = " + triggerStatus + " AND " + where;
        }
        return "SELECT t.`id` AS `id` FROM " + q(staging) + "." + q(table) + " t WHERE " + where
                + scopeClause("t", scopeMinId);
    }

    /** 范围钳制表达式（单侧/双侧自适应） */
    private static String clampExpr(String col, String min, String max) {
        if (min != null && !min.isBlank() && max != null && !max.isBlank()) {
            return "LEAST(GREATEST(" + col + ", " + min + "), " + max + ")";
        }
        if (min != null && !min.isBlank()) return "GREATEST(" + col + ", " + min + ")";
        return "LEAST(" + col + ", " + max + ")";
    }

    /** 作用域限定（增量时只修正新 ID 区间）：`AND t.id > scopeMinId` */
    private static String scopeClause(String alias, Long scopeMinId) {
        return scopeMinId == null ? "" : " AND " + alias + ".`id` > " + scopeMinId;
    }

    private static String q(String identifier) {
        return MysqlJdbcDataSource.quote(identifier);
    }
}
