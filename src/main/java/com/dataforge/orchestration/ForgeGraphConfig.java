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
 * START → analyze → generateStage → validate ─┬─ (收敛 / 全量模式) ──→ report → END
 *                                             └─ (增量且未收敛) ──→ correct → report → END
 * </pre>
 *
 * <p><b>修正在图上只有一跳</b>：收敛循环留在 {@link com.dataforge.correction.CorrectionEngine} 内部
 * （跨轮"问题清单闭环"是它的收敛要素之一），图上再套回边会对同一批修正做二次循环，
 * 把轮数放大成上限的平方。轮级回边是后续把循环下沉到图上时的方案，需先把跨轮回归基准移交状态。</p>
 *
 * <p><b>图实例为 Spring 单例</b>：实测并发安全（30 线程并发 0 串扰），每次运行靠 threadId 隔离。
 * 节点内部不持有跨运行可变状态（生成计划随状态/上下文流转）。</p>
 *
 * <p><b>检查点</b>：内存版（{@link MemorySaver}），只服务单次运行内的中断恢复；
 * {@code releaseThread(true)} 使运行<b>正常跑完</b>后释放线程检查点（框架仅在走到 END 时释放，
 * 中断/异常仍保留，可继续 resume），避免单例 saver 在长驻 Web 进程里永久滞留每次运行的快照。
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
        graph.addEdge(ForgeNodes.CORRECT, ForgeNodes.REPORT);
        graph.addEdge(ForgeNodes.REPORT, END);

        return graph.compile(CompileConfig.builder()
                .checkpointSaver(new MemorySaver())
                .releaseThread(true)      // 正常跑完即释放检查点（中断仍保留 → 可 resume），防单例 saver 内存滞留
                .recursionLimit(25)       // 无回边，路径最长 5 节点；留足余量兜底
                .build());
    }
}