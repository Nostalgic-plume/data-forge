package com.dataforge.orchestration;

import com.dataforge.config.GeneratorProperties;
import com.dataforge.llm.MockLlmClient;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编排图层测试（沿用项目现有风格：不启动 Spring、不连真实数据库）。
 *
 * <p>覆盖三层：</p>
 * <ol>
 *   <li><b>图结构</b>——节点齐全、无修正回边（回边 + 引擎内循环 = 轮数平方放大）</li>
 *   <li><b>分支判定</b>——全量不修正 vs 增量未收敛进修正</li>
 *   <li><b>框架机制</b>——用同构 stub 图验证真实图依赖的关键假设：
 *       运行时对象经 {@code RunnableConfig.metadata} 可达节点、LOG 通道累加、
 *       条件边标签路由、跑完释放检查点（防单例 saver 内存滞留）。
 *       节点内的 DB / Agent 逻辑需真实 MySQL，不在单测范围（见 docs/orchestration-plan.md）。</li>
 * </ol>
 */
class ForgeGraphTest {

    private static final int MAX_ITERATIONS = 3;

    private static GeneratorProperties props() {
        return new GeneratorProperties(false, 42L, 50, 4, "sequential", null, 5, MAX_ITERATIONS);
    }

    /**
     * 只验证图结构与分支判定——图构建期不会调用节点方法，故节点内的真实依赖此处无需就位。
     */
    private static ForgeNodes nodes() {
        return new ForgeNodes(null, null, new MockLlmClient(), props(), null);
    }

    private static ForgeState state(String mode, int errors, int iteration) {
        return new ForgeState(Map.of(
                ForgeState.MODE, mode,
                ForgeState.ERROR_COUNT, errors,
                ForgeState.ITERATION, iteration));
    }

    // ---------- ① 图结构 ----------

    @Test
    void graphBuildsWithAllNodes() throws Exception {
        CompiledGraph<ForgeState> graph = new ForgeGraphConfig().forgeGraph(nodes());
        assertNotNull(graph, "图应构建成功");

        String mermaid = graph.getGraph(GraphRepresentation.Type.MERMAID).content();

        for (String node : List.of(ForgeNodes.ANALYZE, ForgeNodes.GENERATE,
                ForgeNodes.VALIDATE, ForgeNodes.CORRECT, ForgeNodes.REPORT)) {
            assertTrue(mermaid.contains(node), "图应包含节点: " + node);
        }
        // 条件边的两个分支标签
        assertTrue(mermaid.contains("|" + ForgeNodes.EDGE_RETRY + "|"), "应有 retry 分支");
        assertTrue(mermaid.contains("|" + ForgeNodes.EDGE_DONE + "|"), "应有 done 分支");
    }

    /** 修正只走一跳：correct → report；不得存在 correct → validate 回边（会与引擎内循环叠加成平方） */
    @Test
    void correctDoesNotLoopBackToValidate() throws Exception {
        CompiledGraph<ForgeState> graph = new ForgeGraphConfig().forgeGraph(nodes());
        String mermaid = graph.getGraph(GraphRepresentation.Type.MERMAID).content();

        assertTrue(mermaid.contains(ForgeNodes.CORRECT + ":::correct --> " + ForgeNodes.REPORT + ":::report"),
                "correct 应直接进 report，实际图:\n" + mermaid);
        assertTrue(!mermaid.contains(ForgeNodes.CORRECT + ":::correct --> " + ForgeNodes.VALIDATE + ":::validate"),
                "不应存在 correct → validate 回边（修正轮数由 CorrectionEngine 内部上限控制），实际图:\n" + mermaid);
    }

    // ---------- ② 分支判定 ----------

    /** 全量模式：与现网 /api/generate 行为一致——校验完即收工，不进入修正 */
    @Test
    void fullModeFinishesRightAfterValidate() {
        assertEquals(ForgeNodes.EDGE_DONE,
                nodes().afterValidate(state(ForgeState.MODE_FULL, 3, 0)),
                "全量模式即使有错误也不应进入修正（保持现网行为）");
    }

    @Test
    void incrementalWithErrorsGoesToCorrect() {
        assertEquals(ForgeNodes.EDGE_RETRY,
                nodes().afterValidate(state(ForgeState.MODE_INCREMENTAL, 3, 0)),
                "增量模式有错误且未达上限应进入修正");
    }

    @Test
    void incrementalWithoutErrorsFinishes() {
        assertEquals(ForgeNodes.EDGE_DONE,
                nodes().afterValidate(state(ForgeState.MODE_INCREMENTAL, 0, 0)),
                "增量模式无错误应直接收工");
    }

    /** 残留旧轮数不干扰分支判定：轮数上限在引擎内，图不分轮（否则继承的 iteration 会静默跳过修正） */
    @Test
    void residualIterationDoesNotSkipCorrection() {
        assertEquals(ForgeNodes.EDGE_RETRY,
                nodes().afterValidate(state(ForgeState.MODE_INCREMENTAL, 5, MAX_ITERATIONS)),
                "增量模式有错就应进修正；轮数上限由 CorrectionEngine 判定，图不据分轮数跳过");
    }

    // ---------- ③ 状态契约默认值 ----------

    /** 未校验前不得宣称已收敛（默认 true 会把"未修正的错误"说成已收敛） */
    @Test
    void stateDoesNotClaimConvergedBeforeValidation() {
        ForgeState fresh = new ForgeState(Map.of());
        assertEquals(false, fresh.converged(), "未校验的默认状态不应宣称收敛");
        assertEquals(false, fresh.rolledBack(), "默认不应标记为已回滚");
        assertEquals(0, fresh.fixCount(), "默认修正条数为 0");
        assertNull(fresh.cacheEnabled(), "缓存开关未指定时应用 null（由配置 cache-dir 决定），不写死 false");
        assertNull(fresh.minParentRows(), "父表缺省行数未指定时应用 null（由配置决定）");
        assertTrue(fresh.replan(), "增量 replan 默认 true（与现网 /api/incremental 一致）");
        assertTrue(fresh.tableNames().isEmpty(), "表名默认空");
        assertTrue(fresh.plan() != null, "生成计划默认空计划（非 null，避免调用方 NPE）");
        assertNull(fresh.scopeMinId(), "全量场景无作用域下界（可空字段读取不得 NPE）");
    }

    // ---------- ④ 框架机制（真实图依赖的假设）----------

    /** 与生产图同构的 stub 图：节点不碰 DB，只验证机制 */
    private static CompiledGraph<ForgeState> stubGraph(AtomicReference<RunnableConfig> seen) throws Exception {
        Map<String, Channel<?>> channels = new HashMap<>();
        channels.put(ForgeState.LOG, Channels.appender(ArrayList::new));
        StateGraph<ForgeState> graph = new StateGraph<>(channels, ForgeState::new);

        graph.addNode(ForgeNodes.ANALYZE, AsyncNodeActionWithConfig.node_async((s, cfg) -> {
            seen.set(cfg);        // 关键假设：节点内能拿到 RunnableConfig（运行时对象走 metadata）
            return Map.of(ForgeState.LOG, "analyze");
        }));
        graph.addNode(ForgeNodes.VALIDATE, AsyncNodeActionWithConfig.node_async((s, cfg) ->
                Map.of(ForgeState.ERROR_COUNT, 1, ForgeState.LOG, "validate")));
        graph.addNode(ForgeNodes.CORRECT, AsyncNodeActionWithConfig.node_async((s, cfg) ->
                Map.of(ForgeState.LOG, "correct")));
        graph.addNode(ForgeNodes.REPORT, AsyncNodeActionWithConfig.node_async((s, cfg) ->
                Map.of(ForgeState.LOG, "report")));

        graph.addEdge(START, ForgeNodes.ANALYZE);
        graph.addEdge(ForgeNodes.ANALYZE, ForgeNodes.VALIDATE);
        graph.addConditionalEdges(ForgeNodes.VALIDATE,
                AsyncEdgeAction.edge_async((ForgeState s) -> s.errorCount() > 0
                        ? ForgeNodes.EDGE_RETRY : ForgeNodes.EDGE_DONE),
                Map.of(ForgeNodes.EDGE_RETRY, ForgeNodes.CORRECT,
                       ForgeNodes.EDGE_DONE, ForgeNodes.REPORT));
        graph.addEdge(ForgeNodes.CORRECT, ForgeNodes.REPORT);
        graph.addEdge(ForgeNodes.REPORT, END);

        return graph.compile(org.bsc.langgraph4j.CompileConfig.builder()
                .checkpointSaver(new MemorySaver())
                .releaseThread(true)
                .recursionLimit(25)
                .build());
    }

    /** metadata 透传 + 条件边路由 + LOG 累加（默认覆盖语义会丢轨迹） */
    @Test
    void metadataReachesNodesAndLogAccumulates() throws Exception {
        AtomicReference<RunnableConfig> seen = new AtomicReference<>();
        CompiledGraph<ForgeState> graph = stubGraph(seen);
        Object session = new Object();
        RunnableConfig cfg = RunnableConfig.builder().threadId("t-mech")
                .addMetadata(ForgeState.META_SESSION, session).build();

        ForgeState end = graph.invoke(Map.of(ForgeState.MODE, ForgeState.MODE_INCREMENTAL), cfg).orElseThrow();

        assertNotNull(seen.get(), "节点应收到 RunnableConfig");
        assertSame(session, seen.get().metadata(ForgeState.META_SESSION).orElseThrow(),
                "运行时对象经 metadata 应原样可达节点（会话/report 不进状态的前提）");
        assertEquals(List.of("analyze", "validate", "correct", "report"), end.log(),
                "条件边 retry 应路由到 correct；LOG 通道应累加四个节点的轨迹（覆盖语义会只剩最后一条）");
    }

    /** 正常跑完释放检查点：单例 saver 不得长期持有历次运行的快照（长驻 Web 会线性涨堆） */
    @Test
    void completedRunReleasesCheckpoints() throws Exception {
        CompiledGraph<ForgeState> graph = stubGraph(new AtomicReference<>());
        RunnableConfig cfg = RunnableConfig.builder().threadId("t-release").build();

        Optional<ForgeState> end = graph.invoke(Map.of(ForgeState.MODE, ForgeState.MODE_FULL), cfg);
        assertTrue(end.isPresent(), "图应跑完");
        assertTrue(graph.getStateHistory(cfg).isEmpty(),
                "跑完应释放该线程检查点（releaseThread(true)），否则每次运行都留一份快照在堆里");
    }
}
