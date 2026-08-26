package com.dataforge.report;

import com.dataforge.agent.AnalysisAgent;
import com.dataforge.agent.CorrectionAgent;
import com.dataforge.agent.GenerationAgent;
import com.dataforge.model.ValidationReport;
import com.dataforge.validation.Scorer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行报告（可观测性）：聚合一次运行的各阶段指标 → 统一 JSON。
 *
 * <p>消费现有各阶段结果（GenerationResult / ValidationReport / CorrectionResult / ScoreReport /
 * AnalysisResult），<b>不重新采集</b>——给"一次生成做得好不好 / 花了多少成本"一个统一视角。
 * 未经历阶段留空（不误导为 0）。CLI 打印 + 落盘 {@code run-reports/{ts}.json}。</p>
 *
 * <p>面试叙事：一次运行的全部可靠性/成本/质量指标聚合成一份快照——
 * LLM 批次数 / 降批 / 补差 / 失败表 / 缓存命中 / 校验错误 / 修正轮数 / 收敛状态 / 软评分。</p>
 */
public final class RunReport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Path REPORT_DIR = Path.of("run-reports");

    private final Map<String, Object> data = new LinkedHashMap<>();
    private final long startMs;

    public RunReport() {
        this.startMs = System.currentTimeMillis();
        data.put("mode", "unknown");
    }

    public RunReport mode(String mode) {
        data.put("mode", mode);
        return this;
    }

    /** 生成阶段：表数 / 行数 / LLM 批次数 / 降批 / 补差 / 缓存命中 / 失败表 / 耗时 / LLM 调用数 */
    public RunReport generation(GenerationAgent.GenerationResult r) {
        if (r == null) return this;
        data.put("tableCount", r.tableStats().size());
        data.put("rowCount", r.actualCounts().values().stream().mapToInt(Integer::intValue).sum());
        data.put("llmBatches", r.tableStats().values().stream()
                .mapToInt(GenerationAgent.TableStat::llmBatches).sum());
        data.put("downshifts", r.tableStats().values().stream()
                .mapToInt(GenerationAgent.TableStat::downshifts).sum());
        data.put("gapFills", r.tableStats().values().stream()
                .mapToInt(GenerationAgent.TableStat::gapFills).sum());
        data.put("cacheHits", (int) r.tableStats().values().stream()
                .filter(GenerationAgent.TableStat::cacheHit).count());
        data.put("failedTables", r.failedTables());
        data.put("llmCalls", r.tableStats().values().stream()
                .mapToInt(GenerationAgent.TableStat::llmBatches).sum());
        data.put("generateMs", r.elapsedMs());
        return this;
    }

    /** 校验阶段（SQL 校验）：发现问题行 / 分桶 / 耗时 */
    public RunReport validation(ValidationReport r) {
        if (r == null) return this;
        data.put("validateErrors", r.totalErrors());
        data.put("validateBuckets", r.buckets().size());
        data.put("validateMs", r.elapsedMs());
        return this;
    }

    /** 修正阶段：轮数 / 修正条数 / 收敛 / 回归回滚 / 残留错误 */
    public RunReport correction(CorrectionAgent.CorrectionResult r) {
        if (r == null) return this;
        data.put("correctIterations", r.iterations());
        data.put("correctFixes", r.appliedFixes().size());
        data.put("converged", r.converged());
        data.put("rolledBack", r.rolledBack());
        data.put("residualErrors", r.finalReport().totalErrors());
        return this;
    }

    /** 评分阶段：软评分总分 */
    public RunReport scoring(Scorer.ScoreReport r) {
        if (r == null) return this;
        data.put("scoreOverall", r.overall());
        return this;
    }

    /** 分析阶段：提炼规则总数（前置 + 后置） */
    public RunReport analysis(AnalysisAgent.AnalysisResult r) {
        if (r == null) return this;
        data.put("ruleCount", r.rules().generationRules().size() + r.rules().correctionRules().size());
        return this;
    }

    /** 展开为有序 Map（含总耗时 = 各阶段耗时之和；未填阶段用报告窗口兜底） */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>(data);
        long phaseSum = 0;
        for (String k : List.of("generateMs", "validateMs", "correctMs")) {
            Object v = data.get(k);
            if (v instanceof Number n) phaseSum += n.longValue();
        }
        out.put("totalMs", phaseSum > 0 ? phaseSum : (System.currentTimeMillis() - startMs));
        return out;
    }

    public String toJson() {
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toMap());
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /** 打印 JSON 摘要 + 落盘（CLI 各分支统一出口） */
    public void printAndSave() {
        save();
        System.out.println("\n=== 运行报告（run-reports/{ts}.json）===");
        System.out.println(toJson());
    }

    /** 落盘 run-reports/{ts}.json（一次运行的完整快照；失败不阻断主流程） */
    public void save() {
        try {
            Files.createDirectories(REPORT_DIR);
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                    REPORT_DIR.resolve(System.currentTimeMillis() + ".json").toFile(), toMap());
        } catch (Exception e) {
            // 落盘失败仅告警（调用方日志已输出报告内容）
        }
    }
}
