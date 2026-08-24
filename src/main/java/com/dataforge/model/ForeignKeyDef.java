package com.dataforge.model;

/**
 * 外键定义（API_CONTRACT §2.1）。
 *
 * @param column    本地列名（引用方）
 * @param refTable  被引用表名
 * @param refColumn 被引用列名（通常为 id）
 */
public record ForeignKeyDef(
        String column,
        String refTable,
        String refColumn
) {
}
