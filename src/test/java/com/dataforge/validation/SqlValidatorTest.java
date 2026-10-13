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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL 校验器测试（mock JdbcDataSource，不连真实库）：验证 Detect 扫描 + Aggregate 分桶。
 */
class SqlValidatorTest {

    /** shop 18 表 DDL（测试资源，自包含不依赖外部仓库） */
    private static final String SHOP_DDL = "schema/shop_schema.sql";
    private static List<TableMeta> shopTables;

    @BeforeAll
    static void parseShopDdl() throws Exception {
        shopTables = new DdlParser().parse(readShopDdl()).tables();
    }

    private static String readShopDdl() throws Exception {
        try (var in = SqlValidatorTest.class.getResourceAsStream("/" + SHOP_DDL)) {
            if (in == null) throw new IllegalStateException("测试资源缺失: " + SHOP_DDL);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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
            if (sql.contains("ABS(")) {   // EXPR_EQUAL 行内表达式一致性检查（AGG_EQUAL 不含 ABS）
                return List.of(Map.of("id", 7L, "actual", 0.0, "expected", 88.0));
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

    // ---- EXPR_EQUAL（docs/tech-debt-design.md 步骤 2：actual_amount 一致性可被发现） ----

    /** project_rules.json 中 orders.actual_amount 那条规则（表/列/表达式与实物一致） */
    private static ProjectRules.Rule actualAmountRule() {
        return new ProjectRules.Rule("orders", "actual_amount", "EXPR_EQUAL",
                "实付金额 = 订单金额 - 优惠金额 + 运费", 1.0,
                ProjectRules.Rule.Source.USER_OVERRIDE,
                Map.of("expr", "total_amount - discount_amount + freight"));
    }

    @Test
    void exprEqualRuleCompilesOnRealShopDdl() {
        // 用真实 shop 表结构编译实物规则：列名对不上会在此抛（配置与 DDL 漂移的守卫）
        String sql = new MetaRuleCompiler().compileProjectRule(actualAmountRule(), shopTables, "shop_staging");
        assertTrue(sql.contains("ABS(t.`actual_amount` - (`total_amount` - `discount_amount` + `freight`))"),
                "应编译为 actual_amount 与表达式的差异断言: " + sql);
        assertTrue(sql.contains("AS `expected`"), "差异行应带 expected 供修正侧消费");
    }

    @Test
    void detectsExprEqualMismatch() {
        // 只放 orders 表 + 一条 EXPR_EQUAL 规则：验证规则真的被跑到并分桶（不是只编译不执行）
        List<TableMeta> ordersOnly = shopTables.stream()
                .filter(t -> t.name().equals("orders")).toList();
        ProjectRules rules = new ProjectRules(List.of(), List.of(actualAmountRule()), List.of(), 1);
        ValidationReport report = new SqlValidator(new FakeJdbc(), new MetaRuleCompiler(), fakeStaging())
                .validate(ordersOnly, rules, "shop");

        ValidationReport.Bucket expr = report.buckets().stream()
                .filter(b -> b.errorType().equals("EXPR_EQUAL")).findFirst().orElseThrow(
                        () -> new AssertionError("actual_amount 不一致必须被分桶发现，实际分桶: "
                                + report.buckets().stream().map(ValidationReport.Bucket::errorType).toList()));
        assertEquals(1, expr.count(), "1 行差异应计 1");
        assertFalse(expr.rootCause().isBlank(), "分桶应带根因假设（喂修正 agent）");
    }
}
