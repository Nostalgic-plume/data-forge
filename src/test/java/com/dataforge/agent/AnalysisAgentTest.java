package com.dataforge.agent;

import com.dataforge.db.JdbcDataSource;
import com.dataforge.db.SchemaReader;
import com.dataforge.llm.MockLlmClient;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;
import com.dataforge.parser.DdlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分析 agent 单测（P6）：mock information_schema + mock LLM ——
 * 确定性读库结构 → 规则提炼（含置信度）→ project_rules 母版。
 */
class AnalysisAgentTest {

    /** mock JDBC：模拟 information_schema 查询（orders/order_item 最小结构） */
    private static class FakeJdbc implements JdbcDataSource {

        @Override
        public List<Map<String, Object>> query(String sql, Object... params) {
            if (sql.contains("FROM information_schema.COLUMNS")) return columns();
            if (sql.contains("REFERENCED_TABLE_NAME IS NOT NULL")) return foreignKeys();
            return uniqueKeys();  // 非 PK 非 FK 的唯一约束
        }

        /** orders/order_item 列（含 COMMENT 语义，喂规则提炼） */
        private List<Map<String, Object>> columns() {
            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(col("orders", "id", "bigint", "PRI", "", ""));
            rows.add(col("orders", "order_no", "varchar(32)", "UNI", "", "订单编号（必须唯一）"));
            rows.add(col("orders", "user_id", "bigint", "MUL", "", "用户ID（引用 user.id）"));
            rows.add(col("orders", "total_amount", "decimal(10,2)", "", "", "订单原价总额（= 所有商品项小计之和）"));
            rows.add(col("orders", "status", "tinyint", "", "", "状态：0待支付 1已支付 2已发货 3已收货 4已完成"));
            rows.add(col("orders", "logistics_no", "varchar(32)", "", "", "物流单号"));
            rows.add(col("order_item", "id", "bigint", "PRI", "", ""));
            rows.add(col("order_item", "order_id", "bigint", "MUL", "", "订单ID（引用 orders.id）"));
            rows.add(col("order_item", "subtotal", "decimal(10,2)", "", "", "小计 = 单价 × 数量"));
            return rows;
        }

        private Map<String, Object> col(String table, String column, String type,
                                        String key, String extra, String comment) {
            Map<String, Object> row = new java.util.HashMap<>();
            row.put("table_name", table);
            row.put("column_name", column);
            row.put("column_type", type);
            row.put("is_nullable", "NO");
            row.put("column_key", key);
            row.put("column_comment", comment);
            row.put("extra", extra);
            return row;
        }

        private List<Map<String, Object>> foreignKeys() {
            return List.of(
                    fk("orders", "user_id", "user", "id"),
                    fk("order_item", "order_id", "orders", "id"));
        }

        private Map<String, Object> fk(String table, String column, String refTable, String refColumn) {
            Map<String, Object> row = new java.util.HashMap<>();
            row.put("table_name", table);
            row.put("column_name", column);
            row.put("ref_table", refTable);
            row.put("ref_column", refColumn);
            return row;
        }

        private List<Map<String, Object>> uniqueKeys() {
            Map<String, Object> row = new java.util.HashMap<>();
            row.put("table_name", "orders");
            row.put("column_name", "order_no");
            row.put("constraint_name", "uk_order_no");
            return List.of(row);
        }

        @Override public int execute(String sql, Object... params) { return 0; }
        @Override public long maxId(String schema, String table) { return 0; }
        @Override public void transaction(Supplier<Void> work) { work.get(); }
    }

    /** P6 验收：分析 agent 自动提炼 shop 规则（金额 AGG_EQUAL + 状态权重），低置信度项可确认 */
    @Test
    void extractsRulesFromSchema() {
        SchemaReader schemaReader = new SchemaReader(new FakeJdbc());
        DefaultAnalysisAgent agent = new DefaultAnalysisAgent(schemaReader, new MockLlmClient());

        AnalysisAgent.AnalysisResult result = agent.analyze(
                new AnalysisAgent.AnalysisRequest(null, "shop", List.of()));

        // 提炼规则：金额对账归后置修正（AGG_EQUAL），状态权重在前置约束
        assertTrue(result.rules().generationRules().stream()
                        .noneMatch(r -> r.type().equals("AGG_EQUAL")),
                "金额对账应归后置修正（前置只放生成约束）");
        assertTrue(result.rules().generationRules().stream()
                        .anyMatch(r -> r.type().equals("ENUM_WEIGHT") && r.column().equals("status")),
                "前置约束应含状态权重规则");
        // correctionRules：后置修正（AGG_EQUAL 金额对账，编译校验/修正 SQL）
        assertTrue(result.rules().correctionRules().stream()
                        .anyMatch(r -> r.type().equals("AGG_EQUAL") && r.params().get("ref_table").equals("order_item")),
                "后置修正应含金额对账（可执行）");
        // 行数推荐（按关联）
        assertFalse(result.rowRecommend().isEmpty());
        assertTrue(result.rowRecommend().getOrDefault("order_item", 0) > 0);
        // 缓存指纹
        assertFalse(result.fingerprint().schemaFingerprint().isBlank());
        assertFalse(result.fingerprint().rulesFileHash().isBlank());
        // RuleSetters（决策 B 方式 1）：ENUM_WEIGHT 参数 → plan 列模式 weighted
        var statusPlan = result.plan().tablePlan("orders").columns().stream()
                .filter(c -> c.column().equals("status")).findFirst().orElse(null);
        assertNotNull(statusPlan, "orders.status 应有列模式");
        assertEquals("weighted", statusPlan.mode(), "ENUM_WEIGHT 规则应落 plan 列模式");
        assertEquals("4:0.85", statusPlan.param("weights"), "状态权重参数应生效");
    }

    /** Mock extractRules logistics 启发式：表含 logistics_no + status → CUSTOM_SQL 进后置修正（可执行） */
    @Test
    void customSqlRoutedToCorrectionRules() {
        SchemaReader schemaReader = new SchemaReader(new FakeJdbc());
        DefaultAnalysisAgent agent = new DefaultAnalysisAgent(schemaReader, new MockLlmClient());
        AnalysisAgent.AnalysisResult result = agent.analyze(
                new AnalysisAgent.AnalysisRequest(null, "shop", List.of()));
        assertTrue(result.rules().correctionRules().stream()
                        .anyMatch(r -> r.type().equals("CUSTOM_SQL")
                                && !r.params().getOrDefault("check_sql", "").isBlank()),
                "CUSTOM_SQL 应被路由到 correctionRules（含 check_sql）");
        assertTrue(result.rules().generationRules().stream()
                        .noneMatch(r -> r.type().equals("CUSTOM_SQL")),
                "CUSTOM_SQL 不应落入前置约束（generationRules）");
    }

    /** 分析结果可序列化为 ProjectRules（落盘 project_rules.json 供管线加载） */
    @Test
    void analysisResultSerializes() throws Exception {
        SchemaReader schemaReader = new SchemaReader(new FakeJdbc());
        DefaultAnalysisAgent agent = new DefaultAnalysisAgent(schemaReader, new MockLlmClient());
        AnalysisAgent.AnalysisResult result = agent.analyze(
                new AnalysisAgent.AnalysisRequest(null, "shop", List.of()));

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result.rules());
        assertTrue(json.contains("AGG_EQUAL"), "rules 应可序列化（含 AGG_EQUAL）");
        // 反序列化回 ProjectRules（管线加载路径）
        ProjectRules loaded = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, ProjectRules.class);
        assertEquals(result.rules().correctionRules().size(), loaded.correctionRules().size());
    }

    /** 决策 A 冒烟（修 D2）：projectPath 指向临时源码，分析正常（接入源码语义不崩） */
    @Test
    void analyzeWithSourcePathDoesNotBreak(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("entity");
        Files.createDirectories(src);
        Files.writeString(src.resolve("OrdersDO.java"),
                "package demo;\n@TableName(\"orders\")\npublic class OrdersDO {\n"
                        + "  private Long id;\n  private Integer status;\n}\n");
        SchemaReader schemaReader = new SchemaReader(new FakeJdbc());
        DefaultAnalysisAgent agent = new DefaultAnalysisAgent(schemaReader, new MockLlmClient());
        AnalysisAgent.AnalysisResult result = agent.analyze(
                new AnalysisAgent.AnalysisRequest(tempDir.toString(), "shop", List.of()));
        assertNotNull(result.plan(), "分析应产出生成计划");
        assertNotNull(result.plan().tablePlan("orders"), "orders 应有计划");
        assertFalse(result.rules().correctionRules().isEmpty(), "金额对账规则仍应提炼");
    }

    /** 工具感知列分类（新域集成）：source 表产生列工具决策（工具 + llm），derived 表保持派生 */
    @Test
    void analyzeNewDomainProducesColumnDecisions() throws Exception {
        String ddl = "CREATE TABLE member (\n"
                + "  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '会员ID',\n"
                + "  name VARCHAR(50) NOT NULL COMMENT '会员姓名',\n"
                + "  phone VARCHAR(20) NOT NULL COMMENT '手机号',\n"
                + "  status TINYINT DEFAULT 0 COMMENT '0正常 1停用'\n"
                + ");\n"
                + "CREATE TABLE book (\n"
                + "  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '书ID',\n"
                + "  title VARCHAR(100) NOT NULL COMMENT '书名',\n"
                + "  author VARCHAR(50) NOT NULL COMMENT '作者',\n"
                + "  isbn VARCHAR(20) NOT NULL COMMENT 'ISBN',\n"
                + "  stock INT NOT NULL COMMENT '库存',\n"
                + "  publish_date DATE COMMENT '出版日期'\n"
                + ");\n"
                + "CREATE TABLE borrow (\n"
                + "  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '借阅ID',\n"
                + "  borrow_no VARCHAR(20) NOT NULL COMMENT '借阅编号',\n"
                + "  member_id BIGINT NOT NULL COMMENT '会员ID',\n"
                + "  book_id BIGINT NOT NULL COMMENT '书ID',\n"
                + "  borrow_date DATETIME COMMENT '借出日期',\n"
                + "  return_date DATETIME COMMENT '归还日期',\n"
                + "  status TINYINT DEFAULT 0 COMMENT '0借出 1已还',\n"
                + "  UNIQUE KEY uk_borrow_no (borrow_no)\n"
                + ");";
        List<TableMeta> tables = new DdlParser().parse(ddl).tables();
        DefaultAnalysisAgent agent = new DefaultAnalysisAgent(null, new MockLlmClient());
        AnalysisAgent.AnalysisResult result = agent.analyzeTables(tables);

        // borrow（derived，有 FK）：FK 列保持 fk 模式（不因决策变化）
        var borrow = result.plan().tablePlan("borrow");
        assertNotNull(borrow, "borrow 应有计划");
        assertTrue(borrow.columns().stream()
                        .anyMatch(c -> c.column().equals("member_id") && c.mode().equals("fk")),
                "borrow.member_id 应保持 fk 派生");
        // source 表（book）产生列决策：stock 数值 → valueRange 工具；title 语义 → llm
        var book = result.plan().tablePlan("book");
        assertNotNull(book, "book 应有计划");
        assertFalse(book.columns().isEmpty(), "source 表应产生列工具决策");
        assertEquals("valueRange", book.columns().stream()
                        .filter(c -> c.column().equals("stock")).findFirst().orElseThrow().mode(),
                "book.stock 数值列应决策为 valueRange 工具");
        assertEquals("llm", book.columns().stream()
                        .filter(c -> c.column().equals("title")).findFirst().orElseThrow().mode(),
                "book.title 语义列应决策为 llm");
    }
}
