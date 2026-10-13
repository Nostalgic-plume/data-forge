package com.dataforge.db;

import com.dataforge.model.ColumnDef;
import com.dataforge.model.ForeignKeyDef;
import com.dataforge.model.TableMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 确定性读库结构（P6 分析 agent 的表结构来源）：从 information_schema 读表/列/COMMENT/
 * 枚举/FK/唯一键，输出与 {@code DdlParser} 一致的 {@link TableMeta}。
 *
 * <p>读库走确定性 JDBC（PLAN 决策 #15"读 MCP、写 JDBC"——MCP 是分析 agent 的 LLM 自主工具层，
 * 此处用 JDBC 确定性读取作为 CLI 下的落地通道，McpClient 接口位置在 API_CONTRACT §4 预留）。</p>
 */
@Component
public class SchemaReader {

    private static final Logger log = LoggerFactory.getLogger(SchemaReader.class);

    private final JdbcDataSource jdbc;

    public SchemaReader(JdbcDataSource jdbc) {
        this.jdbc = jdbc;
    }

    /** 从数据库读全部表结构 */
    public List<TableMeta> readTables(String schemaName) {
        List<Map<String, Object>> columns = jdbc.query(
                "SELECT TABLE_NAME AS table_name, COLUMN_NAME AS column_name, COLUMN_TYPE AS column_type,"
                        + " IS_NULLABLE AS is_nullable, COLUMN_KEY AS column_key,"
                        + " COLUMN_COMMENT AS column_comment, EXTRA AS extra"
                        + " FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ?"
                        + " ORDER BY TABLE_NAME, ORDINAL_POSITION", schemaName);
        List<Map<String, Object>> fks = jdbc.query(
                "SELECT TABLE_NAME AS table_name, COLUMN_NAME AS column_name,"
                        + " REFERENCED_TABLE_NAME AS ref_table, REFERENCED_COLUMN_NAME AS ref_column"
                        + " FROM information_schema.KEY_COLUMN_USAGE"
                        + " WHERE TABLE_SCHEMA = ? AND REFERENCED_TABLE_NAME IS NOT NULL", schemaName);
        List<Map<String, Object>> uks = jdbc.query(
                "SELECT TABLE_NAME AS table_name, COLUMN_NAME AS column_name, CONSTRAINT_NAME AS constraint_name"
                        + " FROM information_schema.KEY_COLUMN_USAGE"
                        + " WHERE TABLE_SCHEMA = ? AND REFERENCED_TABLE_NAME IS NULL"
                        + " AND CONSTRAINT_NAME NOT LIKE 'PRIMARY'", schemaName);

        // 按表分组
        Map<String, List<Map<String, Object>>> colsByTable = groupBy(columns, "table_name");
        Map<String, List<Map<String, Object>>> fksByTable = groupBy(fks, "table_name");
        Map<String, List<Map<String, Object>>> uksByTable = groupBy(uks, "table_name");

        List<TableMeta> tables = new ArrayList<>();
        for (String tableName : new TreeSet<>(colsByTable.keySet())) {
            List<ColumnDef> colDefs = colsByTable.get(tableName).stream()
                    .map(this::toColumnDef).toList();
            List<ForeignKeyDef> fkDefs = new ArrayList<>(fksByTable.getOrDefault(tableName, List.of()).stream()
                    .map(r -> new ForeignKeyDef(
                            str(r.get("column_name")), str(r.get("ref_table")), str(r.get("ref_column"))))
                    .toList());
            List<String> uniqueKeys = buildUniqueKeys(uksByTable.getOrDefault(tableName, List.of()));
            tables.add(new TableMeta(tableName, colDefs, fkDefs, uniqueKeys));
        }
        // 隐式 FK 推断（与 DdlParser 一致）：列名 "xxx_id" 且能唯一对应到某张表 → FK(xxx_id → xxx.id)
        // 很多库不建显式外键约束（如 shop），只能依赖 xxx_id 命名约定。三级匹配：
        //   ① 精确      product_id      → product
        //   ② 复数      order_id        → orders
        //   ③ 前缀命名  conversation_id → chat_conversation（要求唯一命中，避免歧义）
        // 必须尽量推全：依赖图（TopologicalSorter）与 FkAlloc 都以本方法推断的外键为唯一数据源，
        // 漏推会使父子表并发展开，父引用列在展开阶段静默取到 null（落库才暴露）。
        Set<String> tableNames = tables.stream().map(TableMeta::name).collect(java.util.stream.Collectors.toSet());
        List<TableMeta> withImplicitFk = new ArrayList<>();
        for (TableMeta t : tables) {
            List<ForeignKeyDef> inferred = new ArrayList<>(t.foreignKeys());
            for (ColumnDef c : t.columns()) {
                if (c.name().endsWith("_id") && !c.primaryKey()
                        && inferred.stream().noneMatch(f -> f.column().equals(c.name()))) {
                    String base = c.name().substring(0, c.name().length() - 3);
                    String refTable = null;
                    if (tableNames.contains(base)) {
                        refTable = base;
                    } else if (tableNames.contains(base + "s")) {
                        refTable = base + "s";
                    } else {
                        List<String> suffixHits = tableNames.stream()
                                .filter(n -> n.endsWith("_" + base)).toList();
                        if (suffixHits.size() == 1) refTable = suffixHits.get(0);
                    }
                    if (refTable != null) {
                        inferred.add(new ForeignKeyDef(c.name(), refTable, "id"));
                    }
                }
            }
            withImplicitFk.add(new TableMeta(t.name(), t.columns(), inferred, t.uniqueKeys()));
        }
        log.info("SchemaReader 读 `{}`：{} 张表（含隐式 FK 推断）", schemaName, tables.size());
        return withImplicitFk;
    }

    private ColumnDef toColumnDef(Map<String, Object> r) {
        String type = str(r.get("column_type"));
        return new ColumnDef(
                str(r.get("column_name")),
                type,
                !"NO".equalsIgnoreCase(str(r.get("is_nullable"))),
                "PRI".equalsIgnoreCase(str(r.get("column_key"))),
                str(r.get("extra")).contains("auto_increment"),
                str(r.get("column_comment")),
                parseEnumValues(type));
    }

    /** 唯一键：按 (表, 约束名) 分组，组内列逗号连接（复合唯一键一组） */
    private List<String> buildUniqueKeys(List<Map<String, Object>> rows) {
        Map<String, List<String>> byConstraint = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            byConstraint.computeIfAbsent(str(r.get("constraint_name")), k -> new ArrayList<>())
                    .add(str(r.get("column_name")));
        }
        return byConstraint.values().stream()
                .map(cols -> String.join(",", cols))
                .sorted()
                .toList();
    }

    /** 解析 MySQL enum 类型：{@code enum('a','b')} → [a, b] */
    private static List<String> parseEnumValues(String columnType) {
        if (columnType == null || !columnType.startsWith("enum")) return List.of();
        int first = columnType.indexOf('(');
        int last = columnType.lastIndexOf(')');
        if (first < 0 || last <= first) return List.of();
        String inner = columnType.substring(first + 1, last);
        // 拆单引号分隔的值（MySQL enum 值一般不含逗号）
        List<String> values = new ArrayList<>();
        for (String part : inner.split(",")) {
            String v = part.trim().replace("'", "");
            if (!v.isEmpty()) values.add(v);
        }
        return values;
    }

    private static Map<String, List<Map<String, Object>>> groupBy(
            List<Map<String, Object>> rows, String key) {
        Map<String, List<Map<String, Object>>> map = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            map.computeIfAbsent(str(r.get(key)), k -> new ArrayList<>()).add(r);
        }
        return map;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
