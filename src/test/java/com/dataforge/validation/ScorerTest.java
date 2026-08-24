package com.dataforge.validation;

import com.dataforge.model.ColumnDef;
import com.dataforge.model.DataRow;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.SourceTag;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 软评分器单测（P7）：KS（数值列 ECDF 距离）+ JS（分类列分布距离）+ 集成评分。
 */
class ScorerTest {

    // ---- KS 距离（数值列） ----

    @Test
    void ksDistanceIdenticalDistributionIsZero() {
        assertEquals(0.0, Scorer.ksDistance(List.of(1.0, 2.0, 3.0), List.of(1.0, 2.0, 3.0)), 1e-9,
                "相同分布 KS 距离应为 0");
    }

    @Test
    void ksDistanceSeparatedDistributionsIsOne() {
        assertEquals(1.0, Scorer.ksDistance(List.of(1.0, 1.0, 1.0), List.of(9.0, 9.0, 9.0)), 1e-9,
                "完全分离分布 KS 距离应为 1");
    }

    // ---- JS 距离（分类列） ----

    @Test
    void jsDistanceIdenticalDistributionIsZero() {
        Map<String, Double> p = Map.of("a", 1.0);
        Map<String, Double> q = Map.of("a", 1.0);
        assertEquals(0.0, Scorer.jsDistance(p, q), 1e-9, "相同分类分布 JS 距离应为 0");
    }

    @Test
    void jsDistanceNonOverlappingIsOne() {
        Map<String, Double> p = Map.of("a", 1.0);
        Map<String, Double> q = Map.of("b", 1.0);
        assertEquals(1.0, Scorer.jsDistance(p, q), 1e-9, "不重叠分类分布 JS 距离应为 1");
    }

    // ---- 集成评分 ----

    /** 构造一列数据的 GeneratedData（数值 total_amount + 分类 status） */
    private static GeneratedData table(String name, List<Double> amounts, List<Integer> statuses) {
        List<ColumnDef> columns = List.of(
                new ColumnDef("id", "BIGINT", false, true, false, "", List.of()),
                new ColumnDef("total_amount", "DECIMAL(10,2)", false, false, false, "", List.of()),
                new ColumnDef("status", "TINYINT", false, false, false, "", List.of("0", "4")));
        List<DataRow> rows = new ArrayList<>();
        for (int i = 0; i < amounts.size(); i++) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", (long) i + 1);
            values.put("total_amount", amounts.get(i));
            values.put("status", statuses.get(i));
            Map<String, SourceTag> lineage = new LinkedHashMap<>();
            for (ColumnDef c : columns) lineage.put(c.name(), SourceTag.SEED);
            rows.add(new DataRow(values, lineage));
        }
        return new GeneratedData(name, columns, rows);
    }

    @Test
    void scoreMatchesReferenceDistribution() {
        Scorer scorer = new Scorer();
        // 生成 vs 参考：分布接近（金额区间不同 → KS 距离中高；状态分布接近 → JS 距离低）
        GeneratedData gen = table("orders",
                List.of(50.0, 60.0, 70.0, 80.0, 90.0),   // 金额 50-90
                List.of(4, 4, 4, 4, 0));                  // 状态 80% 已完成
        GeneratedData ref = table("orders",
                List.of(40.0, 55.0, 65.0, 75.0, 95.0),   // 金额 40-95（近似）
                List.of(4, 4, 4, 4, 0));                  // 状态分布相同
        Scorer.ScoreReport report = scorer.score(Map.of("orders", gen), Map.of("orders", ref));

        Scorer.TableScore ts = report.tables().get("orders");
        assertNotNull(ts);
        // 状态列 JS 距离低（分布相同）
        Scorer.ColumnScore status = ts.columns().stream()
                .filter(c -> c.column().equals("status")).findFirst().orElseThrow();
        assertEquals("JS", status.method());
        assertTrue(status.distance() < 0.01, "状态分布相同 JS 距离应≈0");
        // 金额列 KS 距离存在（分布接近但不完全同）
        Scorer.ColumnScore amount = ts.columns().stream()
                .filter(c -> c.column().equals("total_amount")).findFirst().orElseThrow();
        assertEquals("KS", amount.method());
        assertTrue(amount.distance() >= 0, "金额 KS 距离应 ≥0");
        // 总分 0-1
        assertTrue(report.overall() >= 0 && report.overall() <= 1, "总分应在 0-1");
        // 无参考表 → note 提示
        Scorer.ScoreReport partial = scorer.score(Map.of("orders", gen), Map.of());
        assertFalse(partial.notes().isEmpty(), "无参考分布应有提示");
    }
}
