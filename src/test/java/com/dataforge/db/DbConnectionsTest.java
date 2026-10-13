package com.dataforge.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 凭证兜底的边界测试（安全回归）。
 *
 * <p>约束：只有"目标就是服务端自己配置的那个库"时才允许用服务端凭证。host/port/user
 * 是客户端可控参数，一旦无条件兜底，任何人都能构造
 * {@code {"dbHost":"攻击者的主机","dbUser":"root"}} 让服务端把真实密码发过去——
 * MySQL 认证握手足以让对端拿到凭证。</p>
 */
class DbConnectionsTest {

    private static final String[] KEYS = {"DB_HOST", "DB_PORT", "DB_USER", "DB_PASSWORD"};

    @BeforeEach
    @AfterEach
    void clearDbProperties() {
        for (String k : KEYS) System.clearProperty(k);
    }

    /** 模拟服务端 .env 注入的配置（走系统属性，优先级高于环境变量） */
    private static void serverConfig() {
        System.setProperty("DB_HOST", "localhost");
        System.setProperty("DB_PORT", "3306");
        System.setProperty("DB_USER", "root");
        System.setProperty("DB_PASSWORD", "s3cret");
    }

    @Test
    void 目标与服务端配置一致时才返回凭证() {
        serverConfig();
        assertEquals("s3cret", DbConnections.serverPassword("localhost", "3306", "root"),
                "连的就是服务端自己配置的库 → 允许免密，保留便利性");
    }

    @Test
    void 主机被指向别处_绝不返回凭证() {
        serverConfig();
        assertEquals("", DbConnections.serverPassword("evil.example.com", "3306", "root"),
                "换主机 = 把服务端密码送到对方手里，必须拒绝");
    }

    @Test
    void 端口或用户名不一致_同样不返回凭证() {
        serverConfig();
        assertEquals("", DbConnections.serverPassword("localhost", "3307", "root"), "端口不同 → 拒绝");
        assertEquals("", DbConnections.serverPassword("localhost", "3306", "other"), "用户名不同 → 拒绝");
    }
}
