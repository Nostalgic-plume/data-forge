package com.dataforge.orchestration;

import com.dataforge.config.GeneratorProperties;
import com.dataforge.llm.MockLlmClient;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphRepresentation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编排图层测试（沿用项目现有风格：不启动 Spring、不连真实数据库）。
 *
 * <p>覆盖两点：</p>
 * <ol>
 *   <li><b>图能构建 + 结构正确</b>——防止 Bean 创建在运行期才炸（项目原先没有任何上下文级测试）</li>
 *   <li><b>分支判定正确</b>——全量不收工 vs 增量按收敛情况收工</li>
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

    @Test
    void graphBuildsWithAllNodesAndLoopBackEdge() throws Exception {
        CompiledGraph<ForgeState> graph = new ForgeGraphConfig().forgeGraph(nodes());
        assertNotNull(graph, "图应构建成功");

        String mermaid = graph.getGraph(GraphRepresentation.Type.MERMAID).content();

        for (String node : List.of(ForgeNodes.ANALYZE, ForgeNodes.GENERATE,
                ForgeNodes.VALIDATE, ForgeNodes.CORRECT, ForgeNodes.REPORT)) {
            assertTrue(mermaid.contains(node), "图应包含节点: " + node);
        }
        // 修正循环的关键证据：correct 回到 validate 的回边
        assertTrue(mermaid.contains(ForgeNodes.CORRECT + ":::correct --> " + ForgeNodes.VALIDATE + ":::validate"),
                "应存在 correct -> validate 回边，实际图:\n" + mermaid);
        // 条件边的两个分支标签
        assertTrue(mermaid.contains("|" + ForgeNodes.EDGE_RETRY + "|"), "应有 retry 分支");
        assertTrue(mermaid.contains("|" + ForgeNodes.EDGE_DONE + "|"), "应有 done 分支");
    }

    /** 全量模式：与现网 /api/generate 行为一致——校验完即收工，不进入修正 */
    @Test
    void fullModeFinishesRightAfterValidate() {
        assertEquals(ForgeNodes.EDGE_DONE,
                nodes().afterValidate(state(ForgeState.MODE_FULL, 3, 0)),
                "全量模式即使有错误也不应进入修正（保持现网行为）");
    }

    @Test
    void incrementalWithErrorsLoopsBackToCorrect() {
        assertEquals(ForgeNodes.EDGE_RETRY,
                nodes().afterValidate(state(ForgeState.MODE_INCREMENTAL, 3, 0)),
                "增量模式有错误且未达上限应回到修正");
    }

    @Test
    void incrementalWithoutErrorsFinishes() {
        assertEquals(ForgeNodes.EDGE_DONE,
                nodes().afterValidate(state(ForgeState.MODE_INCREMENTAL, 0, 0)),
                "增量模式无错误应直接收工");
    }

    @Test
    void incrementalStopsAtIterationLimitWithResidualErrors() {
        assertEquals(ForgeNodes.EDGE_DONE,
                nodes().afterValidate(state(ForgeState.MODE_INCREMENTAL, 5, MAX_ITERATIONS)),
                "达到修正轮数上限应带着残留错误收工，避免死循环");
    }
}