package com.dataforge.validation;

import com.dataforge.db.JdbcDataSource;
import com.dataforge.db.StagingManager;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;
import com.dataforge.model.ValidationReport;
import com.dataforge.parser.DdlParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL 校验器测试（mock JdbcDataSource，不连真实库）：验证 Detect 扫描 + Aggregate 分桶。
 */
class SqlValidatorTest {

    private static final String SHOP_DDL = "d:/claudecode/shop/docs/data-gen/schema_for_tool.sql";
    private static List<TableMeta> shopTables;

    @BeforeAll
    static void parseShopDdl() throws Exception {
        shopTables = new DdlParser().parse(Path.of(SHOP_DDL)).tables();
    }

    /** 可编程 mock JDBC：按 SQL 片段返回预设行 */
    private static class FakeJdbc implements JdbcDataSource {
        final List<String> calls = new ArrayList<>();

        @Override
        public List<Map<String, Object>> query(String sql, Object... params) {
            calls.add(sql);
            // 按 SQL 类型返回：FK 检查返回悬挂、唯一检查返回空、金额检查返回差异、行数返回 N
            if (sql.contains("LEFT JOIN")) {
                return List.of(
                        Map.of("id", 1L, "fk_value", 999L, "fk_col", "user_id"),
                        Map.of("id", 2L, "fk_value", 998L, "fk_col", "user_id"));
            }
            if (sql.contains("GROUP BY") && sql.contains("HAVING")) {
                return List.of();
            }
            if (sql.contains("SUM(`subtotal`)")) {
                return List.of(
                        Map.of("id", 1L, "actual", 100.0, "expected", 200.0),
                        Map.of("id", 2L, "actual", 50.0, "expected", 50.5));
            }
            if (sql.contains("COUNT(*)")) {
                return List.of(Map.of("c", 10L));
            }
            return List.of();
        }

        @Override
        public int execute(String sql, Object... params) { return 0; }

        @Override
        public long maxId(String schema, String table) { return 0; }

        @Override
        public void transaction(Supplier<Void> work) { work.get(); }
    }

    private static StagingManager fakeStaging() {
        return new StagingManager() {
            @Override
            public String stagingSchema(String schemaName) { return schemaName + "_staging"; }
            @Override
            public void create(String schemaName, List<TableMeta> tables) { }
            @Override
            public void load(String schemaName, Map<String, com.dataforge.model.GeneratedData> data) { }
            @Override
            public Map<String, com.dataforge.model.GeneratedData> read(String schemaName, List<TableMeta> tables) {
                return Map.of();
            }
            @Override
            public void drop(String schemaName) { }
        };
    }

    @Test
    void detectsFkOrphanAndAmountMismatch() {
        FakeJdbc jdbc = new FakeJdbc();
        SqlValidator validator = new SqlValidator(jdbc, new MetaRuleCompiler(), fakeStaging());

        ValidationReport report = validator.validate(shopTables, ProjectRules.withDefaultCorrectionRules(), "shop");

        // FK 悬挂：user 表（id 引用）2 行悬挂
        assertTrue(report.buckets().stream()
                        .anyMatch(b -> b.errorType().equals("FK_ORPHAN") && b.count() == 2),
                "应分桶出 FK 悬挂");
        // 金额差异（AGG_EQUAL 项目规则，P4 通用化）：orders.total ≠ Σ subtotal
        assertTrue(report.buckets().stream()
                        .anyMatch(b -> b.errorType().equals("AGG_EQUAL") && b.count() == 2),
                "应分桶出聚合相等差异（金额规则来自 correctionRules）");
        // 分桶带根因假设（喂修正 agent）
        ValidationReport.Bucket amount = report.buckets().stream()
                .filter(b -> b.errorType().equals("AGG_EQUAL")).findFirst().orElseThrow();
        assertFalse(amount.rootCause().isBlank(), "分桶应带根因假设");
        assertEquals(2, amount.samples().size(), "样本应含问题行快照（含 id）");
    }

    @Test
    void ruleCompilerGeneratesControlledSql() {
        MetaRuleCompiler compiler = new MetaRuleCompiler();
        TableMeta user = shopTables.stream().filter(t -> t.name().equals("user")).findFirst().orElseThrow();
        TableMeta orderItem = shopTables.stream().filter(t -> t.name().equals("order_item")).findFirst().orElseThrow();

        String ukSql = compiler.compileMetaCheck(user, RuleCompiler.MetaCheckType.UNIQUE_CONFLICT, "shop_staging");
        assertTrue(ukSql.contains("GROUP BY"), "唯一检查 SQL 应 GROUP BY");
        assertTrue(ukSql.contains("HAVING COUNT(*) > 1"), "唯一检查 SQL 应 HAVING");

        String fkSql = compiler.compileMetaCheck(orderItem, RuleCompiler.MetaCheckType.FK_ORPHAN, "shop_staging");
        assertTrue(fkSql.contains("LEFT JOIN"), "FK 检查 SQL 应 LEFT JOIN");
        assertTrue(fkSql.contains("`order_id`"), "FK 检查 SQL 应含外键列");
        // 受控 SQL：标识符反引号包裹
        assertTrue(fkSql.contains("`shop_staging`"), "SQL 应含 staging 库（反引号）");
    }
}
