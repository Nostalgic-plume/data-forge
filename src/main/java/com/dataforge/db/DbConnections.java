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
            pass = serverPassword(host, port, user);   // 前端留空 → 仅当目标就是服务端自己配置的库
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
     *
     * <p><b>仅在 host/port/user 与服务端配置完全一致时才返回凭证。</b>这三项是客户端可控的，
     * 不加这层判断就等于：任何人构造 {@code {"dbHost":"攻击者的主机","dbUser":"root"}} 都能让
     * 服务端把真实密码送过去——MySQL 认证握手足以让对端拿到凭证（比明文回显更隐蔽）。
     * 连别的库必须自带密码。</p>
     */
    static String serverPassword(String host, String port, String user) {
        if (!cfg("DB_HOST", "localhost").equalsIgnoreCase(host)) return "";
        if (!cfg("DB_PORT", "3306").equals(port)) return "";
        if (!cfg("DB_USER", "root").equals(user)) return "";
        return cfg("DB_PASSWORD", "");
    }

    /** 配置项读取：系统属性优先，其次环境变量，最后默认值（启动脚本 set -a; . ./.env 注入环境变量） */
    private static String cfg(String key, String dflt) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) v = System.getenv(key);
        return (v == null || v.isBlank()) ? dflt : v;
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
