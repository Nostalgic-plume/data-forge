package com.dataforge.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 动态数据库连接工厂（Web 前端配置数据库连接）：从请求参数（host/port/user/pass/schema）
 * 创建 DataSource / JdbcDataSource，供分析（读库结构）、落库（staging）使用。
 * 每次请求用前端参数建连（读结构/落库为低频操作，可接受）。
 */
public final class DbConnections {

    private DbConnections() {}

    /** 从前端连接参数创建 DataSource（MySQL） */
    public static DataSource dataSource(Map<String, String> db) {
        String host = orDefault(db.get("dbHost"), "localhost");
        String port = orDefault(db.get("dbPort"), "3306");
        String user = orDefault(db.get("dbUser"), "root");
        String pass = db.get("dbPassword");
        if (pass == null || pass.isBlank()) {
            pass = serverPassword();   // 前端留空 → 用服务端 .env 注入的凭证（密码不经 HTTP 往返）
        }
        String schema = orDefault(db.get("dbSchema"), "");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?useSSL=false&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true"
                + "&useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true";
        DriverManagerDataSource ds = new DriverManagerDataSource(url, user, pass == null ? "" : pass);
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return ds;
    }

    /**
     * 服务端凭证兜底：{@code /api/config} 不再回传密码，前端密码框留空时走这里。
     * 依次查系统属性与环境变量（启动脚本 {@code set -a; . ./.env} 即注入环境变量）。
     */
    private static String serverPassword() {
        String p = System.getProperty("DB_PASSWORD");
        if (p == null || p.isBlank()) p = System.getenv("DB_PASSWORD");
        return p == null ? "" : p;
    }

    /** 从 DataSource 创建确定性 JDBC 通道（JdbcTemplate + 事务模板） */
    public static MysqlJdbcDataSource jdbc(DataSource ds) {
        JdbcTemplate jt = new JdbcTemplate(ds);
        TransactionTemplate tt = new TransactionTemplate(new DataSourceTransactionManager(ds));
        return new MysqlJdbcDataSource(jt, tt);
    }

    private static String orDefault(String v, String dflt) {
        return v == null || v.isBlank() ? dflt : v;
    }
}
