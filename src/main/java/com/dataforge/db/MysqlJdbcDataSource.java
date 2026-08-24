package com.dataforge.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * MySQL 确定性 JDBC 通道（HikariCP + JdbcTemplate，凭证来自 .env 环境变量）。
 *
 * <p>表名/列名统一反引号包裹防注入（SQL 是 RuleCompiler 生成的受控 SQL，非 LLM 自由拼接）。</p>
 */
@Component
public class MysqlJdbcDataSource implements JdbcDataSource {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public MysqlJdbcDataSource(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<Map<String, Object>> query(String sql, Object... params) {
        return jdbcTemplate.queryForList(sql, params);
    }

    @Override
    public int execute(String sql, Object... params) {
        return jdbcTemplate.update(sql, params);
    }

    @Override
    public long maxId(String schema, String table) {
        List<Map<String, Object>> rows = query(
                "SELECT COALESCE(MAX(`id`), 0) AS m FROM `" + schema + "`.`" + table + "`");
        if (rows.isEmpty() || rows.get(0).get("m") == null) return 0;
        return ((Number) rows.get(0).get("m")).longValue();
    }

    @Override
    public void transaction(java.util.function.Supplier<Void> work) {
        transactionTemplate.execute(status -> work.get());
    }

    /** 表名/列名标识符反引号包裹（受控 SQL 用） */
    public static String quote(String identifier) {
        return "`" + identifier.replace("`", "") + "`";
    }
}
