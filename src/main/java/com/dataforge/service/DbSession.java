package com.dataforge.service;

import com.dataforge.db.DbConnections;
import com.dataforge.db.MysqlJdbcDataSource;
import com.dataforge.db.MysqlStagingManager;
import com.dataforge.db.SchemaReader;
import com.dataforge.validation.MetaRuleCompiler;
import com.dataforge.validation.SqlValidator;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 每请求动态数据库会话（Web 前端配置连接）：把一次请求用到的连接资源打包。
 *
 * <p>原为 web 层 Controller 的私有内部类，下沉到 service 的原因：编排层
 * （{@link DataForgeOrchestrator}）也需要它，定义在 service 才能让编排逻辑不依赖 web 包。</p>
 */
public final class DbSession {

    public final DataSource ds;
    public final MysqlJdbcDataSource jdbc;
    public final MysqlStagingManager staging;
    public final SchemaReader schemaReader;
    public final SqlValidator validator;

    public DbSession(Map<String, String> db) {
        this.ds = DbConnections.dataSource(db);
        this.jdbc = DbConnections.jdbc(ds);
        this.staging = new MysqlStagingManager(ds);
        this.schemaReader = new SchemaReader(jdbc);
        this.validator = new SqlValidator(jdbc, new MetaRuleCompiler(), staging);
    }
}