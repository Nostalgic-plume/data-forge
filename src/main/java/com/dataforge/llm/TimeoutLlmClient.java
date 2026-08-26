package com.dataforge.llm;

import com.dataforge.model.ColumnDef;
import com.dataforge.model.ColumnToolDecision;
import com.dataforge.model.ProjectRules;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 调用超时保护装饰器（可靠性增强）：包装 {@link DataLlm}，全部方法经 CompletableFuture.get(timeout)
 * 做<b>总控超时</b>——超时抛异常 → 上层 batch 自适应降批 / 失败表隔离兜底（ContentGen 优化②⑤）。
 *
 * <p>用虚拟线程池：超时阻塞一个虚拟线程不占 OS 线程，代价可忽略；超时后底层调用可能仍在执行
 * （虚拟线程挂起），属罕见路径可接受。</p>
 *
 * <p>装配：真实模式包在熔断/主备外层；mock 模式不包（mock 确定性快速，超时无意义）。</p>
 */
public class TimeoutLlmClient implements DataLlm {

    /** 默认总控超时：须大于最大合法单批耗时（大 batch 单批 2-3min，见 CLAUDE.md 已知边界） */
    private static final long DEFAULT_TIMEOUT_SECONDS = 180;

    private final DataLlm delegate;
    private final long timeoutSeconds;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public TimeoutLlmClient(DataLlm delegate) {
        this(delegate, DEFAULT_TIMEOUT_SECONDS);
    }

    public TimeoutLlmClient(DataLlm delegate, long timeoutSeconds) {
        this.delegate = delegate;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public List<Map<String, Object>> generateRows(String tableName, List<ColumnDef> llmCols,
                                                  int rowCount, String fkContext) {
        return supplyTimeout(() -> delegate.generateRows(tableName, llmCols, rowCount, fkContext));
    }

    @Override
    public List<Map<String, Object>> correctValues(String tableName, List<ColumnDef> columns,
                                                   List<Map<String, Object>> samples) {
        return supplyTimeout(() -> delegate.correctValues(tableName, columns, samples));
    }

    @Override
    public List<ProjectRules.Rule> extractRules(String schemaName, String schemaSummary) {
        return supplyTimeout(() -> delegate.extractRules(schemaName, schemaSummary));
    }

    @Override
    public double scoreSamples(String tableName, List<Map<String, Object>> samples) {
        return supplyTimeout(() -> delegate.scoreSamples(tableName, samples));
    }

    @Override
    public List<ColumnToolDecision> decideColumnTools(String tableName, List<ColumnDef> candidates,
                                                      String toolCatalog) {
        return supplyTimeout(() -> delegate.decideColumnTools(tableName, candidates, toolCatalog));
    }

    @Override
    public Map<String, Integer> recommendRows(String schemaSummary) {
        return supplyTimeout(() -> delegate.recommendRows(schemaSummary));
    }

    @Override
    public List<ProjectRules.Rule> refineRules(List<ProjectRules.Rule> current, String instruction) {
        return supplyTimeout(() -> delegate.refineRules(current, instruction));
    }

    @Override
    public boolean isMock() {
        return delegate.isMock();
    }

    /** 总控超时：提交到虚拟线程池，超时抛异常（→ 上层降批 / 失败表隔离兜底） */
    private <T> T supplyTimeout(Callable<T> callable) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return callable.call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, pool).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("LLM 调用超时（>" + timeoutSeconds + "s）", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException("LLM 调用异常: "
                    + (cause == null ? e.getMessage() : cause.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM 调用被中断", e);
        }
    }
}
