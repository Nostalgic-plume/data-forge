package com.dataforge.model;

import java.util.List;

/**
 * 列定义（API_CONTRACT §2.1）。
 *
 * @param name          列名
 * @param type          SQL 类型（BIGINT / DECIMAL(10,2) / VARCHAR(50) / ENUM ...）
 * @param nullable      是否可空
 * @param primaryKey    是否主键
 * @param autoIncrement 是否自增
 * @param comment       COMMENT 注释（LLM 语义的主要来源）
 * @param enumValues    显式 ENUM 类型的取值列表（非 ENUM 列为空）
 */
public record ColumnDef(
        String name,
        String type,
        boolean nullable,
        boolean primaryKey,
        boolean autoIncrement,
        String comment,
        List<String> enumValues
) {
    /** 标准化类型：去参数，大写（VARCHAR(50) → VARCHAR） */
    public String normalizedType() {
        if (type == null) return "";
        String upper = type.toUpperCase();
        int paren = upper.indexOf('(');
        return paren > 0 ? upper.substring(0, paren) : upper;
    }
}
