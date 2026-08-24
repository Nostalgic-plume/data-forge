package com.dataforge.expansion;

import com.dataforge.model.*;
import com.dataforge.pipeline.PipelineContext;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 通用执行引擎（通用化核心）：读分析 agent 产出的 {@link GenerationPlan.TablePlan} 生成派生表，
 * 换任何业务域只改配置，零 Java 改动。
 *
 * <p>列生成模式（ColumnPlan.mode）：fk（父表池引用）/ seq（序号自动续号）/ weighted（权重分布）
 * / enum（枚举随机）/ pool（值池）/ date（日期范围）/ unique（唯一前缀）/ faker（通用值池）
 * / const（常量）。确定性（PipelineContext 派生 rng），血缘标 EXPAND。</p>
 *
 * <p>跨表聚合（agg_sum）为后续扩展：当前由 Reconciliation 按业务列名探测回填。</p>
 */
@Component
public class GenericExpander {

    /** 从生成计划生成派生表（target = context.rowCount，FK 从父表池随机引用） */
    public List<DataRow> generate(GenerationPlan.TablePlan plan, TableMeta table,
                                  PipelineContext context,
                                  Map<String, Map<String, List<Object>>> completedData) {
        int target = context.rowCount(table.name());
        Random rng = context.newRandom();
        int seqStart = seqOffset(plan, table, completedData);
        Set<String> plannedCols = plan.columns().stream()
                .map(GenerationPlan.ColumnPlan::column).collect(java.util.stream.Collectors.toSet());
        List<DataRow> rows = new ArrayList<>(target);
        for (int i = 0; i < target; i++) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (GenerationPlan.ColumnPlan cp : plan.columns()) {
                values.put(cp.column(), generateValue(cp, table, context, completedData, i, seqStart, rng));
            }
            // 默认列填充：plan 未覆盖的列（避免 NOT NULL 列 null 导致落库失败）
            for (ColumnDef col : table.columns()) {
                if (plannedCols.contains(col.name()) || col.primaryKey()) continue;
                values.put(col.name(), defaultColumnValue(col, i + 1, rng));
            }
            rows.add(tagAll(values, table));
        }
        return rows;
    }

    /**
     * 默认列值（plan 未覆盖的列）：可空 → null；日期/数值 → 类型合理随机值（非 0/占位垃圾）；
     * NOT NULL 字符串列未被计划覆盖 → 抛异常（失败表隔离）——"宁可失败，不可静默占位"。
     */
    private Object defaultColumnValue(ColumnDef col, int index, Random rng) {
        if (col.nullable()) return null;
        String type = col.normalizedType();
        if (type.startsWith("DATE") || type.startsWith("TIME") || type.startsWith("DATETIME")
                || "TIMESTAMP".equals(type)) {
            return ValuePools.randomDateTime(90, rng);
        }
        if (type.startsWith("INT") || type.startsWith("BIGINT") || type.startsWith("SMALLINT")
                || type.startsWith("TINYINT")) {
            return 1 + rng.nextInt(1000);
        }
        if (type.startsWith("DECIMAL") || type.startsWith("NUMERIC")
                || type.startsWith("FLOAT") || type.startsWith("DOUBLE")) {
            return Math.round(rng.nextDouble() * 100000.0 * 100.0) / 100.0;
        }
        throw new IllegalStateException("表列 `" + col.name()
                + "` 未被生成计划覆盖且非空，无法确定性生成，标记失败（绝不静默占位）");
    }

    private Object generateValue(GenerationPlan.ColumnPlan cp, TableMeta table, PipelineContext context,
                                 Map<String, Map<String, List<Object>>> completedData,
                                 int index, int seqStart, Random rng) {
        return switch (cp.mode()) {
            case "fk" -> fkValue(cp, completedData, rng);
            case "anchorPrice" -> anchorPriceValue(cp, completedData, rng);
            case "seq" -> seqValue(cp, index, seqStart);
            case "weighted" -> weightedValue(cp, rng);
            case "enum" -> enumValue(cp, table, rng);
            case "pool" -> poolValue(cp, rng);
            case "date" -> dateValue(cp, rng);
            case "unique" -> seqValue(cp, index, seqStart);
            case "faker" -> fakerValue(cp, rng);
            case "valueRange" -> valueRangeValue(cp, rng);
            case "const" -> cp.param("value");
            case "llm" -> null;   // 语义列（商品名/规格等）：ContentGen 展开后 LLM 批量回填
            default -> null;
        };
    }

    /** FK：从父表（或指定 ref_table）PK 池随机引用（跨现有 + 新增）；zipf=true 走幂律长尾 */
    private Object fkValue(GenerationPlan.ColumnPlan cp,
                           Map<String, Map<String, List<Object>>> completedData, Random rng) {
        String refTable = cp.param("ref_table");
        String refColumn = cp.param("ref_column") != null ? cp.param("ref_column") : "id";
        Map<String, List<Object>> parent = completedData.get(refTable);
        if (parent == null) return null;
        List<Object> pool = parent.get(refColumn);
        if (pool == null || pool.isEmpty()) return null;
        // 幂律长尾（修 D6）：少数父值被引用多、多数少——订单在用户间呈长尾分布
        if ("true".equals(cp.param("zipf"))) {
            return pool.get(Zipf.zipfPick(pool.size(), rng));
        }
        return pool.get(rng.nextInt(pool.size()));
    }

    /** 价格锚点派生（修 D4/D6）：父表锚点价 × 系数（如 0.8/1.0/1.2），跨现有 + 新增 */
    private Object anchorPriceValue(GenerationPlan.ColumnPlan cp,
                                    Map<String, Map<String, List<Object>>> completedData, Random rng) {
        String refTable = cp.param("ref_table");
        String refColumn = cp.param("ref_column") != null ? cp.param("ref_column") : "price";
        Map<String, List<Object>> parent = completedData.get(refTable);
        if (parent == null) return null;
        List<Object> pool = parent.get(refColumn);
        if (pool == null || pool.isEmpty()) return null;
        Object base = pool.get(rng.nextInt(pool.size()));
        double baseVal = base instanceof Number n ? n.doubleValue() : 0.0;
        double ratio = randomRatio(cp.param("coefficients"), rng);
        return Math.round(baseVal * ratio * 100.0) / 100.0;
    }

    /** 系数表随机（"0.8,1.0,1.2" → 取一个；缺省 1.0） */
    private static double randomRatio(String coefficients, Random rng) {
        if (coefficients == null || coefficients.isBlank()) return 1.0;
        String[] parts = coefficients.split(",");
        String pick = parts[rng.nextInt(parts.length)].trim();
        try {
            return Double.parseDouble(pick);
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    /** 序号：prefix + 序号（从现有最大续号，追加不冲突） */
    private String seqValue(GenerationPlan.ColumnPlan cp, int index, int seqStart) {
        String prefix = cp.param("prefix") != null ? cp.param("prefix") : "";
        int width = parseInt(cp.param("width"), 6);
        return prefix + String.format("%0" + width + "d", seqStart + index + 1);
    }

    /** 权重分布：params.weights="4:0.85,0:0.05" → Zipf.weightedPick */
    private Object weightedValue(GenerationPlan.ColumnPlan cp, Random rng) {
        String weights = cp.param("weights");
        if (weights == null) return null;
        String[] parts = weights.split(",");
        int[] values = new int[parts.length];
        double[] w = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String[] kv = parts[i].trim().split(":");
            values[i] = Integer.parseInt(kv[0].trim());
            w[i] = Double.parseDouble(kv[1].trim());
        }
        int idx = Zipf.weightedPick(w, rng);
        return idx >= 0 ? values[idx] : values[0];
    }

    /** 枚举随机：源码枚举（params.values）优先，其次 DB 显式枚举 */
    private Object enumValue(GenerationPlan.ColumnPlan cp, TableMeta table, Random rng) {
        String values = cp.param("values");
        if (values != null && !values.isBlank()) {
            String[] pool = values.split(",");
            return pool[rng.nextInt(pool.length)].trim();
        }
        for (ColumnDef c : table.columns()) {
            if (c.name().equals(cp.column()) && !c.enumValues().isEmpty()) {
                return c.enumValues().get(rng.nextInt(c.enumValues().size()));
            }
        }
        return null;
    }

    /** 值池随机：params.values="A,B,C" */
    private Object poolValue(GenerationPlan.ColumnPlan cp, Random rng) {
        String values = cp.param("values");
        if (values == null) return null;
        String[] pool = values.split(",");
        return pool[rng.nextInt(pool.length)].trim();
    }

    /** 日期：params.range="-90d,0d"（相对今天的天数区间） */
    private String dateValue(GenerationPlan.ColumnPlan cp, Random rng) {
        String range = cp.param("range");
        int pastDays = 90;
        if (range != null && range.contains(",")) {
            pastDays = Math.abs(parseInt(range.split(",")[0].trim().replace("d", ""), 90));
        }
        return ValuePools.randomDateTime(pastDays, rng);
    }

    /** 通用值池：type=name/phone/address/email/remark/logistics */
    private String fakerValue(GenerationPlan.ColumnPlan cp, Random rng) {
        String type = cp.param("type") != null ? cp.param("type") : "name";
        return switch (type) {
            case "phone" -> ValuePools.randomPhone(rng);
            case "address" -> ValuePools.randomAddress(rng);
            case "email" -> "user" + (1000 + rng.nextInt(9000)) + "@qq.com";
            case "remark" -> ValuePools.randomRemark(rng);
            case "logistics" -> ValuePools.randomLogisticsNo(rng);
            default -> ValuePools.randomChineseName(rng);
        };
    }

    /** 取值范围（RuleSetters VALUE_RANGE）：纯数字 min/max 区间随机；表达式（含字母）回退 null 留白 */
    private Object valueRangeValue(GenerationPlan.ColumnPlan cp, Random rng) {
        Double min = parseNumber(cp.param("min"));
        Double max = parseNumber(cp.param("max"));
        if (min == null && max == null) return null;
        double lo = min != null ? min : 0;
        double hi = max != null ? max : 100000;
        return Math.round((lo + rng.nextDouble() * (hi - lo)) * 100.0) / 100.0;
    }

    /** 序号起始（增量续号）：从现有最大序号 +1（追加不冲突） */
    private int seqOffset(GenerationPlan.TablePlan plan, TableMeta table,
                          Map<String, Map<String, List<Object>>> completedData) {
        Map<String, List<Object>> existing = completedData.get(table.name());
        if (existing == null) return 0;
        int max = 0;
        for (GenerationPlan.ColumnPlan cp : plan.columns()) {
            if (!cp.mode().equals("seq") && !cp.mode().equals("unique")) continue;
            List<Object> values = existing.get(cp.column());
            if (values == null) continue;
            String prefix = cp.param("prefix") != null ? cp.param("prefix") : "";
            for (Object v : values) {
                if (v instanceof String s && s.startsWith(prefix)) {
                    try {
                        max = Math.max(max, (int) Long.parseLong(s.substring(prefix.length())));
                    } catch (NumberFormatException ignored) {
                        // 非标准序号忽略
                    }
                }
            }
        }
        return max;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 纯数字解析（valueRange 用；表达式/空返回 null） */
    private static Double parseNumber(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static DataRow tagAll(Map<String, Object> values, TableMeta table) {
        Map<String, com.dataforge.model.SourceTag> lineage = new LinkedHashMap<>();
        for (ColumnDef col : table.columns()) {
            lineage.put(col.name(), com.dataforge.model.SourceTag.EXPAND);
        }
        return new DataRow(values, lineage);
    }
}
