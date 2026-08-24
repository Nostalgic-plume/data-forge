package com.dataforge.db;

import com.dataforge.model.GeneratedData;
import com.dataforge.model.TableMeta;

import java.util.List;
import java.util.Map;

/**
 * staging 工作库生命周期（API_CONTRACT §4）：生成/修正/校验在隔离工作库，
 * 收敛 + 人工确认后发布，回滚 = 重建工作区（PLAN 决策 #6）。
 */
public interface StagingManager {

    /** staging 库名：shop → shop_staging（STAGING_SUFFIX 配置） */
    String stagingSchema(String schemaName);

    /** 建 staging 库 + 按 DDL 元数据建表 */
    void create(String schemaName, List<TableMeta> tables);

    /** 批量导入生成数据（一次 INSERT，rewriteBatchedStatements） */
    void load(String schemaName, Map<String, GeneratedData> data);

    /** 读回 staging 现有数据（修正 agent 的 source of truth 读取；血缘简化标 SEED） */
    Map<String, GeneratedData> read(String schemaName, List<TableMeta> tables);

    /** 清理 staging 库（回滚 = 重建） */
    void drop(String schemaName);
}
