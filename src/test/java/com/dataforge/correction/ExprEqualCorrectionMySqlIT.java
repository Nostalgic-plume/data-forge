package com.dataforge.correction;

import com.dataforge.db.MysqlJdbcDataSource;
import com.dataforge.derivation.ExprSql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * EXPR_EQUAL 修正通道的真实 MySQL 端到端。
 *
 * <p>链路：真库造一行错值 → {@code exprEqualCheckSql} 找到差异行并给出期望值 →
 * {@code exprEqualApplySql} 重算覆盖 → 再 check 归零，且本来正确的行不被误伤。</p>
 *
 * <p>模板单测只能证明 SQL <b>形态</b>对；"这条 SQL 在真库上真的能跑通并改对"只有打到真库才算数。
 * 默认不跑（IT 后缀 + 需 {@code DB_PASSWORD}），用独立临时库，跑完 drop。</p>
 */
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class ExprEqualCorrectionMySqlIT {

    private static final String IT_SCHEMA = "df_expr_fix_it";

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
    void 修正通道在真库上能找到差异行并改对且不误伤() throws Exception {
        MysqlJdbcDataSource jdbc = connect();
        try {
            jdbc.execute("DROP DATABASE IF EXISTS `" + IT_SCHEMA + "`");
            jdbc.execute("CREATE DATABASE `" + IT_SCHEMA + "`");
            jdbc.execute("CREATE TABLE `" + IT_SCHEMA + "`.`orders` ("
                    + " `id` BIGINT PRIMARY KEY,"
                    + " `total_amount` DECIMAL(10,2) NOT NULL,"
                    + " `discount_amount` DECIMAL(10,2) NOT NULL,"
                    + " `freight` DECIMAL(10,2) NOT NULL,"
                    + " `actual_amount` DECIMAL(10,2) NOT NULL)");
            // 1、2 行自洽；3 行错：100 - 10 + 5 应为 95.00，却写了 0
            jdbc.execute("INSERT INTO `" + IT_SCHEMA + "`.`orders` VALUES "
                    + "(1,  30.00, 10.00, 12.00, 32.00),"
                    + "(2,   5.00,  0.00,  0.00,  5.00),"
                    + "(3, 100.00, 10.00,  5.00,  0.00)");

            String sqlExpr = ExprSql.toSql("total_amount - discount_amount + freight", "测试规则");

            List<Map<String, Object>> bad = jdbc.query(CorrectionSqls.exprEqualCheckSql(
                    IT_SCHEMA, "orders", "actual_amount", sqlExpr, null));
            assertEquals(1, bad.size(), "只应有 id=3 一行不一致");
            assertEquals(3L, ((Number) bad.get(0).get("id")).longValue(), "差异行应锚定到 id=3");
            assertEquals(0, new BigDecimal("95.00").compareTo((BigDecimal) bad.get(0).get("expected")),
                    "expected 应为重算出的 95.00");

            jdbc.execute(CorrectionSqls.exprEqualApplySql(
                    IT_SCHEMA, "orders", "actual_amount", sqlExpr, null));

            assertEquals(0, jdbc.query(CorrectionSqls.exprEqualCheckSql(
                            IT_SCHEMA, "orders", "actual_amount", sqlExpr, null)).size(),
                    "修正后再 check 应归零");

            BigDecimal row1 = jdbc.query(
                            "SELECT `actual_amount` FROM `" + IT_SCHEMA + "`.`orders` WHERE `id` = 1")
                    .stream().findFirst().map(r -> (BigDecimal) r.get("actual_amount")).orElseThrow();
            assertEquals(0, new BigDecimal("32.00").compareTo(row1), "本来正确的行不得被误伤");
        } finally {
            jdbc.execute("DROP DATABASE IF EXISTS `" + IT_SCHEMA + "`");
        }
    }
}
