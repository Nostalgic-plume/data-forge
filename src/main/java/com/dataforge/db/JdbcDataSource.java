package com.dataforge.db;

import java.util.List;
import java.util.Map;

/**
 * 确定性 JDBC 通道（API_CONTRACT §4）：校验 / 修正应用 / 发布 / 增量 baseline。
 *
 * <p>与"读路径走 MCP（P6 分析 agent 用）"区分：本通道是确定性代码使用，
 * 性能、事务、可并发。MySQL 方言层预留（方言差异收敛在此接口的实现）。</p>
 */
public interface JdbcDataSource {

    /** 查询：返回行列表（列名 → 值） */
    List<Map<String, Object>> query(String sql, Object... params);

    /** 执行 UPDATE/DELETE/DDL，返回影响行数 */
    int execute(String sql, Object... params);

    /** 增量 baseline：某表 MAX(id)（无表返回 0） */
    long maxId(String schema, String table);

    /** 事务内执行 */
    void transaction(java.util.function.Supplier<Void> work);
}
