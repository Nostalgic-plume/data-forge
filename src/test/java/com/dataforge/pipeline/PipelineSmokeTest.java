package com.dataforge.pipeline;

import com.dataforge.cache.GenerationCache;
import com.dataforge.config.GeneratorProperties;
import com.dataforge.derivation.ReconcileEngine;
import com.dataforge.llm.MockLlmClient;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;
import com.dataforge.model.PipelineContext;
import com.dataforge.parser.DdlParser;
import com.dataforge.validation.DataValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 管线冒烟测试（mock LLM，不启动 Spring 上下文、不调外部服务）。
 *
 * <p>覆盖：DAG 调度 + FK 不悬挂、金额对账、batch 自适应降批、补差不重试、
 * 失败表隔离、生成缓存/断点续跑。参考 real-world-data 的 mock 先例。</p>
 */
class PipelineSmokeTest {

    /** shop 18 表 DDL（测试资源，自包含不依赖外部仓库） */
    private static final String SHOP_DDL = "schema/shop_schema.sql";

    private static List<TableMeta> shopTables;

    @BeforeAll
    static void parseShopDdl() throws Exception {
        DdlParser.DdlParseResult parsed = new DdlParser().parse(readShopDdl());
        assertFalse(parsed.tables().isEmpty(), "shop DDL 应解析出表");
        shopTables = parsed.tables();
    }

    private static String readShopDdl() throws Exception {
        try (var in = PipelineSmokeTest.class.getResourceAsStream("/" + SHOP_DDL)) {
            if (in == null) throw new IllegalStateException("测试资源缺失: " + SHOP_DDL);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static GeneratorProperties props(int batchSize, int concurrency, String cacheDir) {
        return new GeneratorProperties(false, 42L, batchSize, concurrency, "sequential", cacheDir, 5, 10);
    }

    /** 闭包：指定表 + FK 父表（min-parent-rows=5），保持 DDL 顺序 */
    private static List<TableMeta> closure(Set<String> requested) {
        Map<String, TableMeta> all = shopTables.stream()
                .collect(Collectors.toMap(TableMeta::name, t -> t));
        Set<String> closure = new LinkedHashSet<>(requested);
        Deque<String> queue = new ArrayDeque<>(requested);
        while (!queue.isEmpty()) {
            TableMeta meta = all.get(queue.poll());
            if (meta == null) continue;
            for (var fk : meta.foreignKeys()) {
                if (all.containsKey(fk.refTable()) && closure.add(fk.refTable())) {
                    queue.add(fk.refTable());
                }
            }
        }
        return shopTables.stream().filter(t -> closure.contains(t.name())).toList();
    }

    private static Map<String, Integer> rowCounts(List<TableMeta> tables, Map<String, Integer> requested) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TableMeta t : tables) {
            counts.put(t.name(), requested.getOrDefault(t.name(), 5));
        }
        return counts;
    }

    /** 跑一次管线（mock LLM，可注入故障） */
    private record RunResult(ContentGen.GenOutcome outcome, Map<String, GeneratedData> data,
                             DataValidator.ValidationSummary summary) {
    }

    private static RunResult run(List<TableMeta> tables, Map<String, Integer> requested,
                                 GeneratorProperties props, MockLlmClient mock) {
        PipelineContext context = new PipelineContext(tables, rowCounts(tables, requested), props.seed());
        PkGen.execute(context, PkGen.Strategy.SEQUENTIAL);
        FkAlloc.execute(context);
        GenerationCache cache = props.cacheDir() != null
                ? new GenerationCache(Path.of(props.cacheDir())) : null;
        ContentGen.GenOutcome outcome = ContentGen.execute(context, mock, props, cache, null);
        Map<String, GeneratedData> data = outcome.results().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().data()));
        // 金额链回填改由规则驱动（原 legacy Reconciliation 已删除）
        ReconcileEngine.apply(data, ProjectRules.withDefaultCorrectionRules().correctionRules());
        DataValidator.ValidationSummary summary = DataValidator.validate(
                tables, data, Set.copyOf(outcome.failedTables()));
        return new RunResult(outcome, data, summary);
    }

    // =============================================================
    //  验收场景：user20 / product20 / sku80 / orders100
    // =============================================================

    @Test
    void dagSchedulingFkNoOrphan() {
        List<TableMeta> tables = closure(Set.of("user", "product", "sku", "orders"));
        Map<String, Integer> requested = Map.of("user", 20, "product", 20, "sku", 80, "orders", 100);
        RunResult r = run(tables, requested, props(50, 4, null), new MockLlmClient());

        // 引用闭包自动纳入父表 shop/category
        assertTrue(r.outcome().results().containsKey("shop"), "闭包应自动纳入 shop");
        assertTrue(r.outcome().results().containsKey("category"), "闭包应自动纳入 category");

        // 行数达标
        assertEquals(20, r.outcome().results().get("user").data().rowCount());
        assertEquals(20, r.outcome().results().get("product").data().rowCount());
        assertEquals(80, r.outcome().results().get("sku").data().rowCount());
        assertEquals(100, r.outcome().results().get("orders").data().rowCount());
        assertEquals(5, r.outcome().results().get("shop").data().rowCount());

        // FK 无悬挂、唯一键无冲突、无失败表
        assertEquals(0, r.summary().fkOrphanCount(), "FK 不应悬挂");
        assertEquals(0, r.summary().uniqueConflictCount(), "唯一键不应冲突");
        assertTrue(r.outcome().failedTables().isEmpty(), "不应有失败表");
    }

    @Test
    void amountReconciliation() {
        List<TableMeta> tables = closure(Set.of("user", "product", "sku", "orders", "order_item"));
        Map<String, Integer> requested = Map.of("user", 20, "product", 20, "sku", 80,
                "orders", 100, "order_item", 250);
        RunResult r = run(tables, requested, props(50, 4, null), new MockLlmClient());

        // 金额对账一致（total_amount = Σ subtotal，由 AGG_EQUAL 规则确定性回填）
        assertTrue(r.summary().amountChecked(), "orders 与 order_item 同时生成，金额应被核对");
        assertEquals(0, r.summary().amountMismatchCount(), "金额对账应一致");
    }

    // =============================================================
    //  5 优化验证
    // =============================================================

    /** 优化②batch 自适应：50 行批次请求失败 → 降批 25+25 重试，仍成功 */
    @Test
    void batchAdaptiveDownshift() {
        List<TableMeta> tables = closure(Set.of("user", "orders"));
        Map<String, Integer> requested = Map.of("user", 20, "orders", 100);
        MockLlmClient mock = new MockLlmClient()
                .failOnRowCount(50, new RuntimeException("模拟 LLM 超时"));
        RunResult r = run(tables, requested, props(50, 4, null), mock);

        assertEquals(100, r.outcome().results().get("orders").data().rowCount(),
                "降批后应补齐 100 行");
        assertTrue(r.outcome().results().get("orders").downshifts() > 0,
                "应记录降批次数");
        assertEquals(0, r.summary().fkOrphanCount());
    }

    /** 优化①补差不重试：LLM 返回 90% 行 → 只补缺失段（10%），不整批重试 */
    @Test
    void gapFillInsteadOfRetry() {
        List<TableMeta> tables = closure(Set.of("user", "orders"));
        Map<String, Integer> requested = Map.of("user", 20, "orders", 100);
        MockLlmClient mock = new MockLlmClient().shortfallRatio(0.1);
        RunResult r = run(tables, requested, props(50, 4, null), mock);

        int rows = r.outcome().results().get("orders").data().rowCount();
        assertTrue(r.outcome().results().get("orders").gapFills() > 0, "应发生补差");
        // 100 → 90 主批 + 补差 10 → 9 行 ≈ 99 行；接受小缺口（≤2%）
        assertTrue(rows >= 97 && rows <= 100, "补差后行数应接近目标，实际 " + rows);
        assertEquals(0, r.summary().fkOrphanCount(), "FK 仍不应悬挂");
    }

    /** 优化⑤失败表隔离：LLM 表持续失败（降批也救不回）→ 仅该表标记失败，父表照常生成 */
    @Test
    void failedTableIsolation() {
        // chat_message 有 LLM 语义列（content/role），走 LLM → 可注入持续失败
        List<TableMeta> tables = closure(Set.of("chat_message"));
        Map<String, Integer> requested = Map.of("chat_message", 20);
        MockLlmClient mock = new MockLlmClient()
                .failOnTable("chat_message", new RuntimeException("chat_message 表 LLM 持续失败"));
        RunResult r = run(tables, requested, props(50, 4, null), mock);

        assertTrue(r.outcome().failedTables().contains("chat_message"), "chat_message 应标记失败");
        assertTrue(r.outcome().results().containsKey("user"), "父表 user 应照常生成");
        assertTrue(r.outcome().results().containsKey("chat_conversation"), "父表 chat_conversation 应照常生成");
        assertEquals(0, r.summary().fkOrphanCount(), "父表成功时 FK 不应悬挂");
    }

    /** 优化③④生成缓存/断点续跑：同 fingerprint 二次运行命中缓存，LLM 批次数归零 */
    @Test
    void cacheAndResume() {
        String cacheDir = "target/test-cache-" + System.nanoTime();
        List<TableMeta> tables = closure(Set.of("user", "orders"));
        Map<String, Integer> requested = Map.of("user", 20, "orders", 100);

        // 第一次运行（写缓存）
        RunResult first = run(tables, requested, props(50, 4, cacheDir), new MockLlmClient());
        assertTrue(first.outcome().results().values().stream().noneMatch(ContentGen.TableResult::cacheHit),
                "首次运行不应命中缓存");
        assertEquals(100, first.outcome().results().get("orders").data().rowCount());

        // 第二次运行（同 fingerprint → 全部命中）
        RunResult second = run(tables, requested, props(50, 4, cacheDir), new MockLlmClient());
        assertTrue(second.outcome().results().values().stream().allMatch(ContentGen.TableResult::cacheHit),
                "二次运行应全部缓存命中");
        assertEquals(0, second.outcome().results().get("orders").llmBatches(),
                "缓存命中不应有任何 LLM 调用");
        assertEquals(100, second.outcome().results().get("orders").data().rowCount(),
                "缓存数据应完整复用");
    }

    /** 血缘标注：LLM 列标 LLM、FK 列标 FK、PK 列标 PK */
    @Test
    void lineageTags() {
        List<TableMeta> tables = closure(Set.of("user", "orders"));
        Map<String, Integer> requested = Map.of("user", 20, "orders", 100);
        RunResult r = run(tables, requested, props(50, 4, null), new MockLlmClient());

        var orders = r.outcome().results().get("orders").data();
        var firstRow = orders.rows().get(0);
        assertEquals(com.dataforge.model.SourceTag.PK, firstRow.lineage().get("id"));
        assertEquals(com.dataforge.model.SourceTag.FK, firstRow.lineage().get("user_id"));
        assertEquals(com.dataforge.model.SourceTag.LLM, firstRow.lineage().get("receiver_name"));
    }
}
