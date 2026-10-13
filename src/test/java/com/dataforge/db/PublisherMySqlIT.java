package com.dataforge.db;

import com.dataforge.model.DataRow;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.TableMeta;
import com.dataforge.parser.DdlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Publisher 的真实 MySQL 端到端：REPLACE 策略必须"清空目标表后再导入"。
 *
 * <p>单测只能证明 SQL 形态对；真库上"旧数据是否真的被清掉、新数据是否完整写入"要实测。
 * 特意用 600 行跨过 {@code INSERT_BATCH=500} 的分批边界，顺带验证多值 INSERT 拼接正确。</p>
 *
 * <p>全程只操作临时库 {@value #IT_SCHEMA}，跑完 drop，<b>不碰任何业务库</b>。
 * 默认不跑（IT 后缀 + 需 {@code DB_PASSWORD}）。</p>
 */
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class PublisherMySqlIT {

    private static final String IT_SCHEMA = "df_publisher_it";
    private static final int NEW_ROWS = 600;

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    private static MysqlJdbcDataSource connect() {
        String url = "jdbc:mysql://" + env("DB_HOST", "localhost") + ":" + env("DB_PORT", "3306")
                + "/?useSSL=false&serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf8"
                + "&allowPublicKeyRetrieval=true";
        DriverManagerDataSource ds = new DriverManagerDataSource(
                url, env("DB_USER", "root"), System.getenv("DB_PASSWORD"));
        return new MysqlJdbcDataSource(new JdbcTemplate(ds), null);
    }

    @Test
    void REPLACE在真库上清空旧数据并完整导入新数据() throws Exception {
        MysqlJdbcDataSource jdbc = connect();
        try {
            jdbc.execute("DROP DATABASE IF EXISTS `" + IT_SCHEMA + "`");
            jdbc.execute("CREATE DATABASE `" + IT_SCHEMA + "`");
            jdbc.execute("CREATE TABLE `" + IT_SCHEMA + "`.`t` ("
                    + " `id` BIGINT PRIMARY KEY, `amount` DECIMAL(10,2) NOT NULL)");
            // 目标表先放 3 行"旧数据"
            for (int i = 1; i <= 3; i++) {
                jdbc.execute("INSERT INTO `" + IT_SCHEMA + "`.`t` VALUES (?, ?)", (long) i, 999.00);
            }

            TableMeta t = new DdlParser()
                    .parse("CREATE TABLE t (id BIGINT PRIMARY KEY, amount DECIMAL(10,2) NOT NULL);")
                    .tables().get(0);
            List<DataRow> rows = new ArrayList<>();
            for (int i = 1; i <= NEW_ROWS; i++) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("id", (long) i);
                v.put("amount", i * 1.5);
                rows.add(new DataRow(v, new LinkedHashMap<>()));
            }
            GeneratedData gd = new GeneratedData("t", t.columns(), rows);

            Publisher.Result r = new Publisher().publish(jdbc, IT_SCHEMA, List.of(t),
                    Map.of("t", gd), Map.of("t", "REPLACE"), true);
            assertEquals(1, r.published(), "应发布成功: " + r.errors());

            List<Map<String, Object>> cnt = jdbc.query(
                    "SELECT COUNT(*) AS c FROM `" + IT_SCHEMA + "`.`t`");
            assertEquals((long) NEW_ROWS, ((Number) cnt.get(0).get("c")).longValue(),
                    "旧数据应被清空，只留新导入的 " + NEW_ROWS + " 行");

            // 跨分批边界后，末行也必须在（验证多值 INSERT 拼接没漏）
            List<Map<String, Object>> last = jdbc.query(
                    "SELECT `amount` FROM `" + IT_SCHEMA + "`.`t` WHERE `id` = " + NEW_ROWS);
            assertEquals(0, new java.math.BigDecimal(String.valueOf(NEW_ROWS * 1.5))
                    .compareTo((java.math.BigDecimal) last.get(0).get("amount")),
                    "分批导入的行应完整写入");
        } finally {
            jdbc.execute("DROP DATABASE IF EXISTS `" + IT_SCHEMA + "`");
        }
    }
}
