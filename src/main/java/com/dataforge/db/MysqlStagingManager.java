package com.dataforge.db;

import com.dataforge.model.ColumnDef;
import com.dataforge.model.DataRow;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.SourceTag;
import com.dataforge.model.TableMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MySQL staging 工作库实现：建库建表（从 DDL 元数据）→ 批量导入 → 清理。
 * 表名/库名反引号包裹（受控 SQL，防注入）。
 */
@Component
public class MysqlStagingManager implements StagingManager {

    private static final Logger log = LoggerFactory.getLogger(MysqlStagingManager.class);

    private static final String STAGING_SUFFIX =
            System.getProperty("STAGING_SUFFIX", "_staging");
    private static final int BATCH_ROWS = 500;

    private final DataSource dataSource;

    public MysqlStagingManager(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String stagingSchema(String schemaName) {
        return schemaName + STAGING_SUFFIX;
    }

    @Override
    public void create(String schemaName, List<TableMeta> tables) {
        String staging = stagingSchema(schemaName);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "CREATE DATABASE IF NOT EXISTS " + MysqlJdbcDataSource.quote(staging)
                             + " DEFAULT CHARACTER SET utf8mb4")) {
            ps.executeUpdate();
            for (TableMeta table : tables) {
                try (PreparedStatement create = conn.prepareStatement(createTableSql(staging, table))) {
                    create.executeUpdate();
                }
            }
            log.info("staging 库就绪：{}（{} 张表）", staging, tables.size());
        } catch (Exception e) {
            throw new IllegalStateException("创建 staging 库失败: " + e.getMessage(), e);
        }
    }

    /** 从 DDL 元数据生成 CREATE TABLE（列类型保留，PK/唯一键/可空） */
    static String createTableSql(String staging, TableMeta table) {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                .append(MysqlJdbcDataSource.quote(staging)).append('.')
                .append(MysqlJdbcDataSource.quote(table.name())).append(" (\n");
        List<String> pkCols = table.columns().stream()
                .filter(ColumnDef::primaryKey).map(ColumnDef::name).toList();
        for (ColumnDef col : table.columns()) {
            sb.append("  ").append(MysqlJdbcDataSource.quote(col.name()))
                    .append(' ').append(col.type())
                    .append(col.nullable() && !col.primaryKey() ? " NULL" : " NOT NULL")
                    .append(",\n");
        }
        if (!pkCols.isEmpty()) {
            sb.append("  PRIMARY KEY (").append(pkCols.stream()
                    .map(MysqlJdbcDataSource::quote).collect(Collectors.joining(", "))).append("),\n");
        }
        for (String uk : table.uniqueKeys()) {
            String cols = java.util.Arrays.stream(uk.split(",")).map(String::trim)
                    .map(MysqlJdbcDataSource::quote).collect(Collectors.joining(", "));
            sb.append("  UNIQUE KEY (").append(cols).append("),\n");
        }
        // 去掉末尾逗号
        int len = sb.length();
        if (sb.charAt(len - 2) == ',') {
            sb.delete(len - 2, len - 1);
        }
        sb.append(')');
        return sb.toString();
    }

    @Override
    public void load(String schemaName, Map<String, GeneratedData> data) {
        String staging = stagingSchema(schemaName);
        try (Connection conn = dataSource.getConnection()) {
            for (GeneratedData tableData : data.values()) {
                if (tableData.rows().isEmpty()) continue;
                try {
                    insertTable(conn, staging, tableData);
                } catch (Exception e) {
                    // 附带失败首行各列长度：截断类错误（Data too long for column 'x'）只给列名，
                    // 而失败行数据随异常丢失，无从判断实际写入了什么；长度足以定位是哪一列出的问题
                    String diag = tableData.rows().isEmpty() ? "-"
                            : tableData.rows().get(0).values().entrySet().stream()
                                    .map(en -> en.getKey() + "=" + String.valueOf(en.getValue()).length() + "字符")
                                    .collect(Collectors.joining(", "));
                    throw new IllegalStateException(
                            "staging 导入 `" + tableData.tableName() + "` 失败: " + e.getMessage()
                                    + "｜失败首行各列长度[" + diag + "]", e);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("staging 批量导入失败: " + e.getMessage(), e);
        }
    }

    private void insertTable(Connection conn, String staging, GeneratedData data) throws Exception {
        List<ColumnDef> columns = data.columns().stream()
                .filter(c -> c.name() != null).toList();
        if (columns.isEmpty()) return;
        String cols = columns.stream().map(ColumnDef::name)
                .map(MysqlJdbcDataSource::quote).collect(Collectors.joining(", "));
        String placeholders = String.join(",", java.util.Collections.nCopies(columns.size(), "?"));
        String sql = "INSERT INTO " + MysqlJdbcDataSource.quote(staging) + '.'
                + MysqlJdbcDataSource.quote(data.tableName()) + " (" + cols + ") VALUES (" + placeholders + ")";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int count = 0;
            for (DataRow row : data.rows()) {
                for (int i = 0; i < columns.size(); i++) {
                    ps.setObject(i + 1, row.values().get(columns.get(i).name()));
                }
                ps.addBatch();
                if (++count % BATCH_ROWS == 0) ps.executeBatch();
            }
            ps.executeBatch();
            log.info("staging 导入 `{}`：{} 行", data.tableName(), count);
        }
    }

    @Override
    public Map<String, GeneratedData> read(String schemaName, List<TableMeta> tables) {
        String staging = stagingSchema(schemaName);
        Map<String, GeneratedData> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            for (TableMeta table : tables) {
                String sql = "SELECT * FROM " + MysqlJdbcDataSource.quote(staging) + '.'
                        + MysqlJdbcDataSource.quote(table.name());
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    List<DataRow> rows = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            values.put(meta.getColumnLabel(i), rs.getObject(i));
                        }
                        Map<String, SourceTag> lineage = new LinkedHashMap<>();
                        for (ColumnDef col : table.columns()) {
                            lineage.put(col.name(), SourceTag.SEED);
                        }
                        rows.add(new DataRow(values, lineage));
                    }
                    result.put(table.name(), new GeneratedData(table.name(), table.columns(), rows));
                } catch (Exception e) {
                    // 表不在 staging（闭包外）→ 跳过，不中断其余表
                    log.warn("staging 读回跳过 `{}`（{}）", table.name(), e.getMessage());
                }
            }
            log.info("staging 读回 `{}`：{} 张表", staging, result.size());
        } catch (Exception e) {
            throw new IllegalStateException("staging 读回失败: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public void drop(String schemaName) {
        String staging = stagingSchema(schemaName);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DROP DATABASE IF EXISTS " + MysqlJdbcDataSource.quote(staging))) {
            ps.executeUpdate();
            log.info("staging 库已清理：{}", staging);
        } catch (Exception e) {
            throw new IllegalStateException("清理 staging 库失败: " + e.getMessage(), e);
        }
    }
}
