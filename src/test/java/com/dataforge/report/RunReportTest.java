package com.dataforge.report;

import com.dataforge.agent.CorrectionAgent;
import com.dataforge.agent.GenerationAgent;
import com.dataforge.model.ValidationReport;
import com.dataforge.validation.Scorer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 运行报告单测（可观测性）：聚合生成/校验/修正/评分指标 + JSON 序列化 + null 阶段安全。
 */
class RunReportTest {

    private static GenerationAgent.GenerationResult generationResult() {
        Map<String, GenerationAgent.TableStat> stats = new LinkedHashMap<>();
        stats.put("user", new GenerationAgent.TableStat(20, 20, 1000, 3, 1, 1, false));
        stats.put("orders", new GenerationAgent.TableStat(100, 98, 2000, 0, 0, 1, true));
        return new GenerationAgent.GenerationResult(
                Map.of(), List.of("failed_x"), 3000,
                Map.of("user", 20, "orders", 98), stats);
    }

    private static ValidationReport validationReport() {
        ValidationReport.Bucket b = new ValidationReport.Bucket(
                "orders", "total_amount", "AGG_EQUAL", 2, 1.0,
                List.of(Map.of("id", 1L)), "金额差异", "AMOUNT_MISMATCH");
        return new ValidationReport(List.of(b), 2, 50L);
    }

    @Test
    void generationAggregatesStats() {
        RunReport report = new RunReport().mode("generate").generation(generationResult());
        Map<String, Object> data = report.toMap();

        assertEquals("generate", data.get("mode"));
        assertEquals(2, data.get("tableCount"), "表数应聚合");
        assertEquals(118, data.get("rowCount"), "行数应聚合（20+98）");
        assertEquals(3, data.get("llmBatches"), "LLM 批次数应求和");
        assertEquals(1, data.get("downshifts"), "降批次数应求和");
        assertEquals(2, data.get("gapFills"), "补差次数应求和");
        assertEquals(1, data.get("cacheHits"), "缓存命中表数");
        assertEquals(3, data.get("llmCalls"), "LLM 调用数应 = llmBatches");
        assertEquals(List.of("failed_x"), data.get("failedTables"), "失败表清单");
        assertEquals(3000L, data.get("generateMs"));
    }

    @Test
    void validationAndCorrectionAggregate() {
        ValidationReport v = validationReport();
        CorrectionAgent.CorrectionResult fix = new CorrectionAgent.CorrectionResult(
                Map.of(), v, true, false, 1, List.of());
        RunReport report = new RunReport().validation(v).correction(fix, 120L);
        Map<String, Object> data = report.toMap();

        assertEquals(2, data.get("validateErrors"), "校验错误行数");
        assertEquals(1, data.get("validateBuckets"), "校验分桶数");
        assertEquals(50L, data.get("validateMs"));
        assertEquals(1, data.get("correctIterations"), "修正轮数");
        assertEquals(0, data.get("correctFixes"), "修正条数");
        assertEquals(120L, data.get("correctMs"), "修正耗时（须计入 totalMs）");
        assertEquals(true, data.get("converged"), "收敛状态");
        assertEquals(false, data.get("rolledBack"), "回归回滚标记");
        assertEquals(2, data.get("residualErrors"), "收敛后残留错误");
    }

    /** 多轮修正：轮数与条数累加，收敛/残留取最后一轮（防只留最后一条导致与状态矛盾） */
    @Test
    void multiRoundCorrectionAccumulates() {
        ValidationReport v = validationReport();
        RunReport report = new RunReport()
                .correction(new CorrectionAgent.CorrectionResult(Map.of(), v, false, false, 2,
                        List.of(new com.dataforge.model.FixCommand(
                                "orders", 1L, Map.of("total_amount", 1), "金额重算"))), 100L)
                .correction(new CorrectionAgent.CorrectionResult(Map.of(), v, true, false, 1, List.of()), 50L);
        Map<String, Object> data = report.toMap();

        assertEquals(3, data.get("correctIterations"), "轮数应跨轮累加（2+1）");
        assertEquals(1, data.get("correctFixes"), "修正条数应跨轮累加（1+0）");
        assertEquals(150L, data.get("correctMs"), "耗时应跨轮累加");
        assertEquals(true, data.get("converged"), "收敛状态取最后一轮");
    }

    @Test
    void scoringAggregate() {
        RunReport report = new RunReport().scoring(new Scorer.ScoreReport(Map.of(), 0.85, List.of()));
        assertEquals(0.85, report.toMap().get("scoreOverall"), "软评分总分");
    }

    @Test
    void toJsonSerializesAllStages() {
        RunReport report = new RunReport().generation(generationResult()).validation(validationReport());
        String json = report.toJson();
        assertTrue(json.contains("\"rowCount\""), "JSON 应含生成指标");
        assertTrue(json.contains("\"validateErrors\""), "JSON 应含校验指标");
        assertTrue(json.contains("\"totalMs\""), "JSON 应含总耗时");
    }

    @Test
    void nullStageIsSkipped() {
        // 未经历阶段传 null → 不抛异常、不写入对应 key（不误导为 0）
        RunReport report = new RunReport().generation(null).validation(null).correction(null, 0L)
                .scoring(null).analysis(null);
        Map<String, Object> data = report.toMap();
        assertFalse(data.containsKey("rowCount"), "未生成阶段不应写入 rowCount");
        assertFalse(data.containsKey("validateErrors"), "未校验阶段不应写入 validateErrors");
        assertFalse(data.containsKey("scoreOverall"), "未评分阶段不应写入 scoreOverall");
        assertFalse(data.containsKey("ruleCount"), "未分析阶段不应写入 ruleCount");
    }
}
