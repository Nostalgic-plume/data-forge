package com.dataforge.parser;

import com.alibaba.druid.DbType;
import org.springframework.stereotype.Component;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLDataType;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLCharExpr;
import com.alibaba.druid.sql.ast.statement.*;
import com.dataforge.model.ColumnDef;
import com.dataforge.model.ForeignKeyDef;
import com.dataforge.model.TableMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * DDL 解析器（移植自 real-world-data 的 DdlParser，Druid SQL 解析）。
 *
 * <p>输出 API_CONTRACT 的 {@link TableMeta} 模型：
 * <ul>
 *   <li>列定义（类型/可空/主键/自增/注释/ENUM 取值）</li>
 *   <li>显式 FOREIGN KEY + 隐式外键推断（{@code xxx_id} 命名规范匹配表名）</li>
 *   <li>UNIQUE KEY 列组（唯一键校验用）</li>
 * </ul>
 */
@Component
public class DdlParser {

    private static final Logger log = LoggerFactory.getLogger(DdlParser.class);

    /** 解析结果：表结构列表 + 非致命错误提示 */
    public record DdlParseResult(List<TableMeta> tables, List<String> errors) {
        public boolean hasErrors() {
            return errors != null && !errors.isEmpty();
        }
    }

    // =============================================================
    //  解析入口
    // =============================================================

    public DdlParseResult parse(String ddlContent) {
        List<TableMeta> tables = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (ddlContent == null || ddlContent.isBlank()) {
            errors.add("DDL 内容为空");
            return new DdlParseResult(tables, errors);
        }

        try {
            List<SQLStatement> statements = SQLUtils.parseStatements(ddlContent, DbType.mysql);
            for (SQLStatement stmt : statements) {
                if (stmt instanceof SQLCreateTableStatement createStmt) {
                    try {
                        TableMeta table = parseCreateTable(createStmt);
                        if (table != null) tables.add(table);
                    } catch (Exception e) {
                        errors.add("解析表结构出错: " + e.getMessage());
                    }
                }
            }
            // 第二步：隐式外键推断（基于 _id 命名规范），不覆盖显式外键
            inferImplicitForeignKeys(tables);
        } catch (Exception e) {
            errors.add("DDL 解析失败: " + e.getMessage());
        }

        if (tables.isEmpty() && errors.isEmpty()) {
            errors.add("未找到任何 CREATE TABLE 语句");
        }
        return new DdlParseResult(tables, errors);
    }

    public DdlParseResult parse(Path filePath) throws Exception {
        return parse(readSqlFile(filePath));
    }

    // =============================================================
    //  单表解析
    // =============================================================

    private TableMeta parseCreateTable(SQLCreateTableStatement stmt) {
        String tableName = extractTableName(stmt);
        if (tableName == null) return null;

        Set<String> pkColumns = findTableLevelPrimaryKeys(stmt);
        List<TableLevelFk> tableFks = findTableLevelForeignKeys(stmt);
        List<List<String>> uniqueGroups = findTableLevelUniqueKeys(stmt);

        List<ColumnDef> columns = new ArrayList<>();
        for (SQLTableElement element : stmt.getTableElementList()) {
            if (element instanceof SQLColumnDefinition colDef) {
                ColumnDef column = parseColumn(colDef, pkColumns, tableFks);
                if (column != null) columns.add(column);
            }
        }

        // 列级 UNIQUE 约束并入唯一键组
        for (SQLTableElement element : stmt.getTableElementList()) {
            if (element instanceof SQLColumnDefinition colDef) {
                boolean colUnique = colDef.getConstraints() != null && colDef.getConstraints().stream()
                        .anyMatch(SQLUnique.class::isInstance);
                if (colUnique) {
                    String colName = cleanIdentifier(colDef.getColumnName());
                    if (colName != null) uniqueGroups.add(List.of(colName));
                }
            }
        }

        // 外键合并到 TableMeta（column/refTable/refColumn）
        List<ForeignKeyDef> foreignKeys = new ArrayList<>();
        for (ColumnDef col : columns) {
            for (TableLevelFk fk : tableFks) {
                if (fk.localColumn.equals(col.name())) {
                    foreignKeys.add(new ForeignKeyDef(fk.localColumn, fk.refTable, fk.refColumn));
                    break;
                }
            }
        }

        // 唯一键列组字符串化（多列组 → "a,b" 形式存组）
        List<String> uniqueKeys = uniqueGroups.stream()
                .map(group -> String.join(",", group))
                .toList();

        return new TableMeta(tableName, columns, foreignKeys, uniqueKeys);
    }

    private String extractTableName(SQLCreateTableStatement stmt) {
        if (stmt.getTableSource() != null && stmt.getTableSource().getTableName() != null) {
            return stmt.getTableSource().getTableName().replace("`", "");
        }
        return null;
    }

    private Set<String> findTableLevelPrimaryKeys(SQLCreateTableStatement stmt) {
        Set<String> pkColumns = new HashSet<>();
        for (SQLTableElement element : stmt.getTableElementList()) {
            if (element instanceof SQLPrimaryKey pk && pk.getColumns() != null) {
                for (SQLSelectOrderByItem item : pk.getColumns()) {
                    pkColumns.add(cleanIdentifier(item.getExpr().toString()));
                }
            }
        }
        return pkColumns;
    }

    private List<TableLevelFk> findTableLevelForeignKeys(SQLCreateTableStatement stmt) {
        List<TableLevelFk> result = new ArrayList<>();
        for (SQLTableElement element : stmt.getTableElementList()) {
            if (element instanceof SQLForeignKeyImpl fk) {
                String refTable = fk.getReferencedTableName() != null
                        ? fk.getReferencedTableName().toString().replace("`", "") : null;
                String refColumn = (fk.getReferencedColumns() != null && !fk.getReferencedColumns().isEmpty())
                        ? fk.getReferencedColumns().get(0).toString().replace("`", "") : "id";
                String localColumn = (fk.getReferencingColumns() != null && !fk.getReferencingColumns().isEmpty())
                        ? fk.getReferencingColumns().get(0).toString().replace("`", "") : null;
                if (localColumn != null && refTable != null) {
                    result.add(new TableLevelFk(localColumn, refTable, refColumn));
                }
            }
        }
        return result;
    }

    private List<List<String>> findTableLevelUniqueKeys(SQLCreateTableStatement stmt) {
        List<List<String>> groups = new ArrayList<>();
        for (SQLTableElement element : stmt.getTableElementList()) {
            if (element instanceof SQLUniqueConstraint unique && unique.getColumns() != null) {
                List<String> group = new ArrayList<>();
                for (SQLSelectOrderByItem item : unique.getColumns()) {
                    group.add(cleanIdentifier(item.getExpr().toString()));
                }
                if (!group.isEmpty()) groups.add(group);
            }
        }
        return groups;
    }

    // =============================================================
    //  列定义解析
    // =============================================================

    private ColumnDef parseColumn(SQLColumnDefinition colDef, Set<String> pkColumns,
                                  List<TableLevelFk> tableFks) {
        String colName = colDef.getColumnName() != null ? cleanIdentifier(colDef.getColumnName()) : null;
        if (colName == null) return null;

        String type = parseDataType(colDef.getDataType());
        List<String> enumValues = List.of();

        boolean hasNotNull = false;
        boolean hasPrimaryKey = false;
        boolean hasUnique = false;
        if (colDef.getConstraints() != null) {
            for (SQLColumnConstraint constraint : colDef.getConstraints()) {
                if (constraint instanceof SQLNotNullConstraint) hasNotNull = true;
                else if (constraint instanceof SQLColumnPrimaryKey) hasPrimaryKey = true;
                else if (constraint instanceof SQLUnique) hasUnique = true;
            }
        }
        boolean hasAutoIncrement = colDef.toString().toLowerCase().contains("auto_increment");

        boolean isPk = pkColumns.contains(colName) || hasPrimaryKey;
        boolean nullable = !hasNotNull && !isPk;

        String comment = null;
        if (colDef.getComment() != null) {
            comment = extractStringValue(colDef.getComment());
        }

        // ENUM 类型的取值列表
        if ("ENUM".equalsIgnoreCase(colDef.getDataType() != null ? colDef.getDataType().getName() : "")
                && colDef.getDataType().getArguments() != null) {
            enumValues = colDef.getDataType().getArguments().stream()
                    .map(this::extractStringValue)
                    .filter(Objects::nonNull)
                    .toList();
        }

        return new ColumnDef(colName, type, nullable, isPk, hasAutoIncrement, comment, enumValues);
    }

    /** 类型字符串：VARCHAR(50) / DECIMAL(10,2) / BIGINT / ENUM 等（保留参数） */
    private String parseDataType(SQLDataType dataType) {
        if (dataType == null || dataType.getName() == null) return null;
        String typeName = dataType.getName().toUpperCase();
        if (dataType.getArguments() == null || dataType.getArguments().isEmpty()
                || "ENUM".equals(typeName) || "JSON".equals(typeName) || "JSONB".equals(typeName)) {
            return typeName;
        }
        StringBuilder sb = new StringBuilder(typeName).append("(");
        for (int i = 0; i < dataType.getArguments().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(dataType.getArguments().get(i));
        }
        return sb.append(")").toString();
    }

    private String extractStringValue(SQLExpr expr) {
        try {
            if (expr instanceof SQLCharExpr charExpr) {
                return charExpr.getText();
            }
            String val = expr.toString();
            if (val != null && val.length() >= 2
                    && ((val.startsWith("'") && val.endsWith("'"))
                    || (val.startsWith("\"") && val.endsWith("\"")))) {
                return val.substring(1, val.length() - 1);
            }
            return val;
        } catch (Exception e) {
            return expr.toString();
        }
    }

    private String cleanIdentifier(String identifier) {
        return identifier == null ? null : identifier.replace("`", "").trim();
    }

    // =============================================================
    //  隐式外键推断（xxx_id 命名规范）
    // =============================================================

    private void inferImplicitForeignKeys(List<TableMeta> tables) {
        Set<String> tableNames = new HashSet<>();
        for (TableMeta t : tables) tableNames.add(t.name());

        List<TableMeta> enriched = new ArrayList<>(tables.size());
        for (TableMeta table : tables) {
            List<ForeignKeyDef> existing = table.foreignKeys();
            List<ForeignKeyDef> result = new ArrayList<>(existing);
            for (ColumnDef col : table.columns()) {
                // 跳过已通过显式 FK 设置的列
                if (existing.stream().anyMatch(fk -> fk.column().equals(col.name()))) continue;

                String colName = col.name();
                if (colName == null || !colName.endsWith("_id") || colName.length() <= 3) continue;

                String candidate = colName.substring(0, colName.length() - 3);
                String matched = matchTableName(candidate, tableNames);
                if (matched != null) {
                    result.add(new ForeignKeyDef(colName, matched, "id"));
                }
            }
            enriched.add(new TableMeta(table.name(), table.columns(), result, table.uniqueKeys()));
        }
        // 原地替换（调用方持有的是传入列表）
        tables.clear();
        tables.addAll(enriched);
    }

    /** 候选表名匹配：精确 → 忽略大小写 → 单数化 → 复数化 */
    private String matchTableName(String candidate, Set<String> tableNames) {
        if (tableNames.contains(candidate)) return candidate;
        for (String name : tableNames) {
            if (name.equalsIgnoreCase(candidate)) return name;
        }
        if (candidate.endsWith("s") && candidate.length() > 1) {
            String singular = candidate.substring(0, candidate.length() - 1);
            for (String name : tableNames) {
                if (name.equalsIgnoreCase(singular)) return name;
            }
        }
        String plural = candidate + "s";
        for (String name : tableNames) {
            if (name.equalsIgnoreCase(plural)) return name;
        }
        return null;
    }

    // =============================================================
    //  文件读取（编码自动检测：UTF-8 → GBK → 系统编码宽松兜底）
    // =============================================================

    public static String readSqlFile(Path filePath) throws Exception {
        byte[] bytes = Files.readAllBytes(filePath);
        String sysEnc = System.getProperty("file.encoding");
        String[] candidates = {"UTF-8", "GBK", sysEnc};
        for (String cs : candidates) {
            if (cs == null) continue;
            try {
                CharsetDecoder decoder = Charset.forName(cs).newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                return decoder.decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException ignored) {
                // 当前编码不符，试下一个
            }
        }
        String fallbackEnc = sysEnc != null ? sysEnc : "UTF-8";
        return new String(bytes, Charset.forName(fallbackEnc));
    }

    // =============================================================
    //  内部记录
    // =============================================================

    /** 表级外键暂存（列名 → 引用表/列） */
    private record TableLevelFk(String localColumn, String refTable, String refColumn) {
    }
}
