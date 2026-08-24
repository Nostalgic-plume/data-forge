package com.dataforge.validation;

import com.dataforge.model.GeneratedData;
import com.dataforge.model.TableMeta;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 软评分器（P7，PLAN §3 ⑤）：统计距离度量（借鉴 SDMetrics：KS/JS 分布距离）+ 语义抽样 LLM 打分。
 *
 * <p>与硬校验（FK/唯一/金额，确定性 SQL）互补：硬校验查"对不对"，软评分量"像不像"。
 * 生成数据 vs 参考分布（真实库或基准）的逐列分布距离 → 0-1 评分。</p>
 *
 * <ul>
 *   <li>数值列：<b>KS</b>（Kolmogorov-Smirnov）两样本 ECDF 最大差</li>
 *   <li>分类列：<b>JS</b>（Jensen-Shannon）离散分布距离（对称化 KL）</li>
 *   <li>总分：列分加权平均（0-1，越高越接近参考分布）</li>
 * </ul>
 */
@Component
public class Scorer {

    /** 评分报告：每表列评分 + 总分 */
    public record ScoreReport(
            Map<String, TableScore> tables,
            double overall,
            List<String> notes
    ) {
    }

    /** 单表评分 */
    public record TableScore(String table, double score, List<ColumnScore> columns) {
    }

    /** 单列评分（method: KS / JS） */
    public record ColumnScore(String column, double distance, double score, String method) {
    }

    /**
     * 生成数据 vs 参考分布 → 逐列分布距离评分。
     *
     * @param generated 生成数据（staging）
     * @param reference 参考数据（真实目标库 / 基准分布），可为空 → 仅对参考可比的表评分
     */
    public ScoreReport score(Map<String, GeneratedData> generated,
                             Map<String, GeneratedData> reference) {
        Map<String, TableScore> tableScores = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        List<Double> allColumnScores = new ArrayList<>();

        for (Map.Entry<String, GeneratedData> e : generated.entrySet()) {
            GeneratedData gen = e.getValue();
            GeneratedData ref = reference == null ? null : reference.get(e.getKey());
            if (ref == null) {
                notes.add("表 `" + e.getKey() + "` 无参考分布（真实库无此表），跳过评分");
                continue;
            }
            TableScore ts = scoreTable(gen, ref);
            tableScores.put(e.getKey(), ts);
            ts.columns().forEach(c -> allColumnScores.add(c.score()));
        }

        double overall = allColumnScores.isEmpty() ? 0.0
                : allColumnScores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return new ScoreReport(tableScores, overall, notes);
    }

    private TableScore scoreTable(GeneratedData gen, GeneratedData ref) {
        List<ColumnScore> columnScores = new ArrayList<>();
        for (com.dataforge.model.ColumnDef col : gen.columns()) {
            List<Object> genVals = columnValues(gen, col.name());
            List<Object> refVals = columnValues(ref, col.name());
            if (genVals.isEmpty() || refVals.isEmpty()) continue;
            ColumnScore cs = isNumeric(col.type()) && !isEnum(col)
                    ? scoreNumeric(genVals, refVals, col.name())
                    : scoreCategorical(genVals, refVals, col.name());
            if (cs != null) columnScores.add(cs);
        }
        double tableScore = columnScores.isEmpty() ? 0.0
                : columnScores.stream().mapToDouble(ColumnScore::score).average().orElse(0.0);
        return new TableScore(gen.tableName(), tableScore, columnScores);
    }

    /** 数值列：KS 两样本 ECDF 最大差（0 = 完全同分布，1 = 完全分离） */
    private ColumnScore scoreNumeric(List<Object> gen, List<Object> ref, String column) {
        List<Double> a = toDoubles(gen);
        List<Double> b = toDoubles(ref);
        if (a.isEmpty() || b.isEmpty()) return null;
        double ks = ksDistance(a, b);
        return new ColumnScore(column, ks, 1 - ks, "KS");
    }

    /** 分类列：JS 离散分布距离（0 = 完全同分布，1 = 完全不重叠） */
    private ColumnScore scoreCategorical(List<Object> gen, List<Object> ref, String column) {
        Map<String, Double> pg = distribution(gen);
        Map<String, Double> pr = distribution(ref);
        double js = jsDistance(pg, pr);
        return new ColumnScore(column, js, 1 - js, "JS");
    }

    /** KS 距离：两样本 ECDF 的最大垂直差（对去重点逐点比较，tie 正确处理） */
    static double ksDistance(List<Double> a, List<Double> b) {
        List<Double> aSorted = new ArrayList<>(a);
        List<Double> bSorted = new ArrayList<>(b);
        Collections.sort(aSorted);
        Collections.sort(bSorted);
        Set<Double> points = new TreeSet<>(aSorted);
        points.addAll(bSorted);
        double maxDiff = 0.0;
        for (double x : points) {
            double fa = ecdf(aSorted, x);
            double fb = ecdf(bSorted, x);
            maxDiff = Math.max(maxDiff, Math.abs(fa - fb));
        }
        return maxDiff;
    }

    private static double ecdf(List<Double> sorted, double x) {
        int count = 0;
        for (double v : sorted) {
            if (v <= x) count++;
        }
        return count / (double) sorted.size();
    }

    /** JS 距离：两离散分布（对称化 KL，值域 0-1） */
    static double jsDistance(Map<String, Double> p, Map<String, Double> q) {
        Set<String> keys = new LinkedHashSet<>(p.keySet());
        keys.addAll(q.keySet());
        double js = 0.0;
        for (String k : keys) {
            double pk = p.getOrDefault(k, 0.0);
            double qk = q.getOrDefault(k, 0.0);
            double m = (pk + qk) / 2.0;
            if (pk > 0) js += 0.5 * pk * log2(pk / m);
            if (qk > 0) js += 0.5 * qk * log2(qk / m);
        }
        return Math.min(1.0, js);
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    // ---- 工具 ----

    private static List<Object> columnValues(GeneratedData data, String column) {
        List<Object> values = new ArrayList<>(data.rowCount());
        for (var row : data.rows()) {
            Object v = row.values().get(column);
            if (v != null) values.add(v);
        }
        return values;
    }

    private static List<Double> toDoubles(List<Object> values) {
        List<Double> out = new ArrayList<>(values.size());
        for (Object v : values) {
            try {
                out.add(((Number) v).doubleValue());
            } catch (Exception ignored) {
                // 非数值（脏值）跳过
            }
        }
        return out;
    }

    /** 分类分布：值 → 频率（归一化） */
    static Map<String, Double> distribution(List<Object> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object v : values) {
            counts.merge(String.valueOf(v), 1, Integer::sum);
        }
        Map<String, Double> dist = new LinkedHashMap<>();
        int total = values.size();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            dist.put(e.getKey(), e.getValue() / (double) total);
        }
        return dist;
    }

    static boolean isNumeric(String type) {
        if (type == null) return false;
        String t = type.toUpperCase();
        return t.startsWith("INT") || t.startsWith("BIGINT") || t.startsWith("SMALLINT")
                || t.startsWith("TINYINT") || t.startsWith("DECIMAL") || t.startsWith("NUMERIC")
                || t.startsWith("FLOAT") || t.startsWith("DOUBLE");
    }

    /** 枚举列视为分类（即使底层数值） */
    private static boolean isEnum(com.dataforge.model.ColumnDef col) {
        return !col.enumValues().isEmpty();
    }
}
