package com.dataforge.validation;

import com.dataforge.db.JdbcDataSource;
import com.dataforge.db.StagingManager;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;
import com.dataforge.model.ValidationReport;
import com.dataforge.parser.DdlParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 根因分桶单测：SqlValidator 为分桶产出 rootCauseKey（同根因可归因批量处理，解决"关联问题散桶"）。
 */
class RootCauseBucketTest {

    /** shop 18 表 DDL（测试资源，自包含不依赖外部仓库） */
    private static final String SHOP_DDL = "schema/shop_schema.sql";
    private static List<TableMeta> shopTables;

    @BeforeAll
    static void parseShopDdl() throws Exception {
        shopTables = new DdlParser().parse(readShopDdl()).tables();
    }

    private static String readShopDdl() throws Exception {
        try (var in = RootCauseBucketTest.class.getResourceAsStream("/" + SHOP_DDL)) {
            if (in == null) throw new IllegalStateException("测试资源缺失: " + SHOP_DDL);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** mock JDBC：FK 悬挂返回（含父表 product 缺失场景）、金额差异返回、其余空 */
    private static class FakeJdbc implements JdbcDataSource {
        @Override
        public List<Map<String, Object>> query(String sql, Object... params) {
            if (sql.contains("LEFT JOIN")) {
                // 悬挂 fk_col = product_id（父表 product 缺失）→ 根因 key 应含 product
                return List.of(
                        Map.of("id", 1L, "fk_value", 999L, "fk_col", "product_id"),
                        Map.of("id", 2L, "fk_value", 998L, "fk_col", "user_id"));
            }
            if (sql.contains("GROUP BY") && sql.contains("HAVING")) return List.of();
            if (sql.contains("SUM(`subtotal`)")) {
                return List.of(Map.of("id", 1L, "actual", 100.0, "expected", 200.0));
            }
            if (sql.contains("COUNT(*)")) return List.of(Map.of("c", 10L));
            return List.of();
        }

        @Override public int execute(String sql, Object... params) { return 0; }
        @Override public long maxId(String schema, String table) { return 0; }
        @Override public void transaction(Supplier<Void> work) { work.get(); }
    }

    private static StagingManager fakeStaging() {
        return new StagingManager() {
            @Override public String stagingSchema(String schemaName) { return schemaName + "_staging"; }
            @Override public void create(String schemaName, List<TableMeta> tables) { }
            @Override public void load(String schemaName, Map<String, com.dataforge.model.GeneratedData> data) { }
            @Override public Map<String, com.dataforge.model.GeneratedData> read(String schemaName, List<TableMeta> tables) {
                return Map.of();
            }
            @Override public void drop(String schemaName) { }
        };
    }

    @Test
    void fkOrphanBucketCarriesRootCauseKey() {
        SqlValidator validator = new SqlValidator(new FakeJdbc(), new MetaRuleCompiler(), fakeStaging());
        ValidationReport report = validator.validate(shopTables, ProjectRules.withDefaultCorrectionRules(), "shop");

        // FK 悬挂桶：rootCauseKey 应带父表名（FK_PARENT_FAIL:{父表}）
        ValidationReport.Bucket fk = report.buckets().stream()
                .filter(b -> b.errorType().equals("FK_ORPHAN")).findFirst().orElseThrow();
        assertTrue(fk.rootCauseKey().startsWith("FK_PARENT_FAIL:"),
                "FK 悬挂应带根因 key，实际: " + fk.rootCauseKey());
        assertTrue(fk.rootCauseKey().length() > "FK_PARENT_FAIL:".length(),
                "根因 key 应含父表名，实际: " + fk.rootCauseKey());
    }

    @Test
    void amountBucketCarriesRootCauseKey() {
        SqlValidator validator = new SqlValidator(new FakeJdbc(), new MetaRuleCompiler(), fakeStaging());
        ValidationReport report = validator.validate(shopTables, ProjectRules.withDefaultCorrectionRules(), "shop");

        ValidationReport.Bucket amount = report.buckets().stream()
                .filter(b -> b.errorType().equals("AGG_EQUAL")).findFirst().orElseThrow();
        assertEquals("AMOUNT_MISMATCH", amount.rootCauseKey(), "金额差异根因 key 应为 AMOUNT_MISMATCH");
    }
}
