package com.dataforge.llm;

import com.dataforge.model.ColumnDef;
import com.dataforge.model.ColumnToolDecision;
import com.dataforge.model.ProjectRules;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 熔断器装饰器（可靠性增强）：连续失败达到阈值 → 打开熔断器快速失败 → 冷却后半开探测。
 *
 * <p>三态：<b>CLOSED</b>（正常）→ 连续失败 ≥ failureThreshold → <b>OPEN</b>（快速失败，不调 LLM）
 * → 冷却 cooldownMillis 后 → <b>HALF_OPEN</b>（放行 1 个试探）→ 成功回 CLOSED / 失败回 OPEN。</p>
 *
 * <p>降级哲学：OPEN 时<b>快速失败</b>（抛异常），由上层 batch 自适应降批 → 失败表隔离兜底——
 * 绝不用"更假的数据"（mock 启发式）降级，宁可靠性失败，不可静默污染。</p>
 *
 * <p>仅 <b>generateRows</b>（高频批量生成）走熔断；其余低频方法（extractRules/correctValues 等）
 * 透传 delegate（由 failover / 调用方降级处理）。</p>
 */
public class CircuitBreakerLlmClient implements DataLlm {

    enum State { CLOSED, OPEN, HALF_OPEN }

    /** 连续失败阈值：达到即 OPEN */
    private static final int DEFAULT_FAILURE_THRESHOLD = 3;
    /** OPEN 冷却期：结束后进入 HALF_OPEN */
    private static final long DEFAULT_COOLDOWN_MILLIS = 30_000;

    private final DataLlm delegate;
    private final int failureThreshold;
    private final long cooldownMillis;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openedAt;
    private final AtomicBoolean halfOpenProbe = new AtomicBoolean();

    public CircuitBreakerLlmClient(DataLlm delegate) {
        this(delegate, DEFAULT_FAILURE_THRESHOLD, DEFAULT_COOLDOWN_MILLIS);
    }

    public CircuitBreakerLlmClient(DataLlm delegate, int failureThreshold, long cooldownMillis) {
        this.delegate = delegate;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownMillis = Math.max(1, cooldownMillis);
    }

    /** 当前熔断状态（测试/观测用） */
    public State state() {
        return state.get();
    }

    /** 连续失败次数（测试/观测用） */
    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    @Override
    public List<Map<String, Object>> generateRows(String tableName, List<ColumnDef> llmCols,
                                                  int rowCount, String fkContext) {
        // ① 熔断前置检查
        if (state.get() == State.OPEN) {
            if (System.currentTimeMillis() - openedAt < cooldownMillis) {
                throw new IllegalStateException(
                        "熔断 OPEN：LLM 调用快速失败（连续失败 " + consecutiveFailures.get()
                                + " 次，冷却中 " + cooldownMillis + "ms）");
            }
            // 冷却结束 → 半开探测
            state.set(State.HALF_OPEN);
        }
        if (state.get() == State.HALF_OPEN && !halfOpenProbe.compareAndSet(false, true)) {
            throw new IllegalStateException("熔断 HALF_OPEN：仅放行试探请求");
        }
        // ② 真实调用
        try {
            List<Map<String, Object>> result =
                    delegate.generateRows(tableName, llmCols, rowCount, fkContext);
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            throw e;
        }
    }

    /** 成功：连续失败清零 → CLOSED（半开探测成功即闭合） */
    private void onSuccess() {
        consecutiveFailures.set(0);
        state.set(State.CLOSED);
        halfOpenProbe.set(false);
    }

    /** 失败：连续计数 +1，达阈值 → OPEN */
    private void onFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            state.set(State.OPEN);
            openedAt = System.currentTimeMillis();
            halfOpenProbe.set(false);
        }
    }

    // ---- 低频方法：透传 delegate（不熔断，由 failover / 调用方降级）----

    @Override
    public List<Map<String, Object>> correctValues(String tableName, List<ColumnDef> columns,
                                                   List<Map<String, Object>> samples) {
        return delegate.correctValues(tableName, columns, samples);
    }

    @Override
    public List<ProjectRules.Rule> extractRules(String schemaName, String schemaSummary) {
        return delegate.extractRules(schemaName, schemaSummary);
    }

    @Override
    public double scoreSamples(String tableName, List<Map<String, Object>> samples) {
        return delegate.scoreSamples(tableName, samples);
    }

    @Override
    public List<ColumnToolDecision> decideColumnTools(String tableName, List<ColumnDef> candidates,
                                                      String toolCatalog) {
        return delegate.decideColumnTools(tableName, candidates, toolCatalog);
    }

    @Override
    public Map<String, Integer> recommendRows(String schemaSummary) {
        return delegate.recommendRows(schemaSummary);
    }

    @Override
    public List<ProjectRules.Rule> refineRules(List<ProjectRules.Rule> current, String instruction) {
        return delegate.refineRules(current, instruction);
    }

    @Override
    public boolean isMock() {
        return delegate.isMock();
    }
}
