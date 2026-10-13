package com.dataforge.validation;

import com.dataforge.db.MysqlJdbcDataSource;
import com.dataforge.db.StagingManager;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;
import com.dataforge.model.ValidationReport;
import com.dataforge.parser.DdlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EXPR_EQUAL 真实 MySQL 端到端验收（docs/tech-debt-design.md 步骤 2 的"可被发现"标准）。
 *
 * <p>链路：真库建表造数 → 真实 {@link SqlValidator} 扫描 → 必须报出 {@code actual_amount} 不一致行。
 * 与 {@code SqlValidatorTest} 的 mock JDBC 互补：这一条证明规则在真库上真的跑得通。</p>
 *
 * <p><b>默认不跑</b>：类名以 IT 结尾，不匹配 surefire 默认 pattern；且需显式提供
 * {@code DB_PASSWORD} 环境变量。用独立临时库 {@value #IT_SCHEMA}，跑完 drop，不碰业务库。
 * 手动执行：{@code set -a; . .env; set +a; mvn -Dtest=ExprEqualMySqlIT test}</p>
 */
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class ExprEqualMySqlIT {

    private static final String IT_SCHEMA = "df_expr_equal_it";

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
        return new MysqlJdbcDataSource(new JdbcTemplate(ds), null);   // transaction 未用，置 null
    }

    /** SqlValidator 只消费 stagingSchema()；指向固定临时库 */
    private static StagingManager stagingTo(String schema) {
        return new StagingManager() {
            @Override public String stagingSchema(String schemaName) { return schema; }
            @Override public void create(String s, List<TableMeta> t) { }
            @Override public void load(String s, Map<String, GeneratedData> d) { }
            @Override public Map<String, GeneratedData> read(String s, List<TableMeta> t) { return Map.of(); }
            @Override public void drop(String s) { }
        };
    }

    @Test
    void exprEqualDetectsMismatchOnRealMySql() throws Exception {
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
            // 1、2 行自洽；3 行故意错：100 - 10 + 5 应为 95，却写了 0
            jdbc.execute("INSERT INTO `" + IT_SCHEMA + "`.`orders` VALUES "
                    + "(1, 30.00, 10.00, 12.00, 32.00),"
                    + "(2,  5.00,  0.00,  0.00,  5.00),"
                    + "(3, 100.00, 10.00,  5.00,  0.00)");

            TableMeta orders = new DdlParser().parse("CREATE TABLE orders ("
                    + " id BIGINT PRIMARY KEY, total_amount DECIMAL(10,2) NOT NULL,"
                    + " discount_amount DECIMAL(10,2) NOT NULL, freight DECIMAL(10,2) NOT NULL,"
                    + " actual_amount DECIMAL(10,2) NOT NULL);").tables().get(0);
            ProjectRules rules = new ProjectRules(List.of(), List.of(
                    new ProjectRules.Rule("orders", "actual_amount", "EXPR_EQUAL",
                            "实付金额 = 订单金额 - 优惠金额 + 运费", 1.0,
                            ProjectRules.Rule.Source.USER_OVERRIDE,
                            Map.of("expr", "total_amount - discount_amount + freight"))),
                    List.of(), 1);

            ValidationReport report = new SqlValidator(jdbc, new MetaRuleCompiler(), stagingTo(IT_SCHEMA))
                    .validate(List.of(orders), rules, IT_SCHEMA);

            ValidationReport.Bucket expr = report.buckets().stream()
                    .filter(b -> b.errorType().equals("EXPR_EQUAL")).findFirst().orElseThrow(
                            () -> new AssertionError("真实 MySQL 上必须报出 actual_amount 不一致，实际分桶: "
                                    + report.buckets().stream()
                                    .map(ValidationReport.Bucket::errorType).toList()));
            assertEquals(1, expr.count(), "只有 id=3 行不一致，应恰好报 1 行");
            assertEquals(3L, ((Number) expr.samples().get(0).get("id")).longValue(),
                    "样本应锚定到问题行的 id");
            assertTrue(!expr.rootCause().isBlank(), "分桶应带根因假设（喂修正 agent）");
        } finally {
            jdbc.execute("DROP DATABASE IF EXISTS `" + IT_SCHEMA + "`");
        }
    }
}
