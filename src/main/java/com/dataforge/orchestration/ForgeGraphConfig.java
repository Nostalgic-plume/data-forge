package com.dataforge.orchestration;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/**
 * 编排图装配（LangGraph4j）。
 *
 * <pre>
 * START → analyze → generateStage → validate ─┐
 *                        ↑                     │ (未收敛)
 *                        └──── correct ←───────┘
 *                                     │ (收敛 / 达上限 / 全量模式)
 *                                     ↓
 *                                  report → END
 * </pre>
 *
 * <p><b>图实例为 Spring 单例</b>：实测并发安全（30 线程并发 0 串扰），每次运行靠 threadId 隔离。</p>
 *
 * <p><b>检查点</b>：当前用内存版（{@link MemorySaver}），只服务单次运行内的暂停/恢复；
 * 跨进程持久化待接 MySQL（见 docs/orchestration-plan.md 的 T6）。</p>
 */
@Configuration
public class ForgeGraphConfig {

    @Bean
    public CompiledGraph<ForgeState> forgeGraph(ForgeNodes nodes) throws Exception {
        Map<String, Channel<?>> channels = new HashMap<>();
        // 执行轨迹是累加的（默认通道是覆盖语义，会被后一个节点冲掉）
        channels.put(ForgeState.LOG, Channels.appender(ArrayList::new));

        StateGraph<ForgeState> graph = new StateGraph<>(channels, ForgeState::new);

        graph.addNode(ForgeNodes.ANALYZE, AsyncNodeActionWithConfig.node_async(nodes::analyze));
        graph.addNode(ForgeNodes.GENERATE, AsyncNodeActionWithConfig.node_async(nodes::generateAndStage));
        graph.addNode(ForgeNodes.VALIDATE, AsyncNodeActionWithConfig.node_async(nodes::validate));
        graph.addNode(ForgeNodes.CORRECT, AsyncNodeActionWithConfig.node_async(nodes::correct));
        graph.addNode(ForgeNodes.REPORT, AsyncNodeActionWithConfig.node_async(nodes::report));

        graph.addEdge(START, ForgeNodes.ANALYZE);
        graph.addEdge(ForgeNodes.ANALYZE, ForgeNodes.GENERATE);
        graph.addEdge(ForgeNodes.GENERATE, ForgeNodes.VALIDATE);
        graph.addConditionalEdges(ForgeNodes.VALIDATE,
                AsyncEdgeAction.edge_async(nodes::afterValidate),
                Map.of(ForgeNodes.EDGE_RETRY, ForgeNodes.CORRECT,
                       ForgeNodes.EDGE_DONE, ForgeNodes.REPORT));
        graph.addEdge(ForgeNodes.CORRECT, ForgeNodes.VALIDATE);
        graph.addEdge(ForgeNodes.REPORT, END);

        return graph.compile(CompileConfig.builder()
                .checkpointSaver(new MemorySaver())
                .releaseThread(false)     // 跑完保留检查点：事后可查执行轨迹（审计/报告）
                .recursionLimit(50)       // 循环兜底（修正轮数上限另有业务判定）
                .build());
    }
}