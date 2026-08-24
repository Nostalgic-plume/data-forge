package com.dataforge.model;

import java.util.List;

/**
 * 表结构元数据（API_CONTRACT §2.1，DdlParser 产出）。
 *
 * @param name         表名
 * @param columns      列定义
 * @param foreignKeys  外键（显式声明 + 隐式推断合并）
 * @param uniqueKeys   唯一键列组（如 ["username"] / ["user_id","sku_id"]）
 */
public record TableMeta(
        String name,
        List<ColumnDef> columns,
        List<ForeignKeyDef> foreignKeys,
        List<String> uniqueKeys
) {
    /** 本表所有外键列名集合（分类器/注入器快速判断用）。 */
    public List<String> fkColumnNames() {
        return foreignKeys.stream().map(ForeignKeyDef::column).toList();
    }
}
