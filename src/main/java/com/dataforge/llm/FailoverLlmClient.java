package com.dataforge.llm;

import com.dataforge.model.ColumnDef;
import com.dataforge.model.ColumnToolDecision;
import com.dataforge.model.ProjectRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多模型 failover（P8，PLAN 决策 #12）：主备双链路，主模型 5xx/限流/超时 → 自动切换备模型。
 *
 * <p>装饰器模式：包装两个 {@link DataLlm}（primary + backup）。<b>generateRows</b> 主失败自动
 * 切换备（failover 计数供成本/可靠性记录）；其余方法（extractRules/correctValues/scoreSamples）
 * 透传主（分析/修正为低频操作，主失败时也可由调用方降级）。</p>
 *
 * <p>装配：真实模式 primary = 主模型客户端（LLM_BASE_URL/LLM_MODEL），backup = 备模型客户端
 * （如 qwen，DashScope 兼容端点 LLM_BACKUP_*）；mock 模式单测直接 new 包装 MockLlmClient。</p>
 */
public class FailoverLlmClient implements DataLlm {

    private static final Logger log = LoggerFactory.getLogger(FailoverLlmClient.class);

    private final DataLlm primary;
    private final DataLlm backup;
    private final AtomicLong failoverCount = new AtomicLong();

    public FailoverLlmClient(DataLlm primary, DataLlm backup) {
        this.primary = primary;
        this.backup = backup;
    }

    /** 累计 failover 次数（成本/可靠性记录，面试叙事） */
    public long failoverCount() {
        return failoverCount.get();
    }

    @Override
    public List<Map<String, Object>> generateRows(String tableName, List<ColumnDef> llmCols,
                                                  int rowCount, String fkContext) {
        try {
            return primary.generateRows(tableName, llmCols, rowCount, fkContext);
        } catch (Exception e) {
            log.warn("主模型失败（{}），failover 到备模型（第 {} 次）",
                    e.getMessage(), failoverCount.incrementAndGet());
            return backup.generateRows(tableName, llmCols, rowCount, fkContext);
        }
    }

    @Override
    public boolean isMock() {
        return primary.isMock();
    }

    // ---- 其余通道：透传主（低频操作，不做 failover，主失败由调用方降级）----
    // 注意：DataLlm 的这些方法都有"退化 default 实现"（空列表 / -1 / 原值），
    // 此处若不显式覆盖，调用会静默落到 default 上——表现为"规则提炼 0 条、评分恒 -1"且无任何告警。

    @Override
    public List<Map<String, Object>> correctValues(String tableName, List<ColumnDef> columns,
                                                   List<Map<String, Object>> samples) {
        return primary.correctValues(tableName, columns, samples);
    }

    @Override
    public List<ProjectRules.Rule> extractRules(String schemaName, String schemaSummary) {
        return primary.extractRules(schemaName, schemaSummary);
    }

    @Override
    public double scoreSamples(String tableName, List<Map<String, Object>> samples) {
        return primary.scoreSamples(tableName, samples);
    }

    @Override
    public List<ColumnToolDecision> decideColumnTools(String tableName, List<ColumnDef> candidates,
                                                      String toolCatalog) {
        return primary.decideColumnTools(tableName, candidates, toolCatalog);
    }

    @Override
    public Map<String, Integer> recommendRows(String schemaSummary) {
        return primary.recommendRows(schemaSummary);
    }

    @Override
    public List<ProjectRules.Rule> refineRules(List<ProjectRules.Rule> current, String instruction) {
        return primary.refineRules(current, instruction);
    }
}
