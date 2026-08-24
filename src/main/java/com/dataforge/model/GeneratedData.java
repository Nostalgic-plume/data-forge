package com.dataforge.model;

import java.util.List;

/**
 * 一张表的生成结果（API_CONTRACT §2.2）。
 *
 * @param tableName 表名
 * @param columns   表结构
 * @param rows      数据行（含血缘）
 */
public record GeneratedData(
        String tableName,
        List<ColumnDef> columns,
        List<DataRow> rows
) {
    public int rowCount() {
        return rows == null ? 0 : rows.size();
    }
}
