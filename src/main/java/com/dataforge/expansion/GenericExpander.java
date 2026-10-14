package com.dataforge.expansion;

import com.dataforge.derivation.ExpressionEvaluator;
import com.dataforge.derivation.ExpressionException;
import com.dataforge.expansion.primitive.CountDistribution;
import com.dataforge.generator.RuleDataGenerator;
import com.dataforge.model.*;
import com.dataforge.model.PipelineContext;
import net.datafaker.Faker;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用执行引擎（通用化核心）：读分析 agent 产出的 {@link GenerationPlan.TablePlan} 生成派生表，
 * 换任何业务域只改配置，零 Java 改动。
 *
 * <p>列生成模式（ColumnPlan.mode）：fk（父表池引用；params.follow 使其跟随同行锚定列取同一
 * 父行的另一列）/ seq（序号自动续号）/ weighted（权重分布）/ enum（枚举随机）/ pool（值池）
 * / date（日期范围）/ unique（唯一前缀）/ faker（通用值池）/ valueRange（区间）/ const（常量）
 * / llm（语义列，生成后由 ContentGen 批量回填）/ expr（行内表达式，只引用同表计划列）。
 * 确定性（PipelineContext 派生 rng），血缘标 EXPAND。</p>
 *
 * <p>跨表聚合与行内一致性<b>不归本类管</b>：分别由 AGG_EQUAL / EXPR_EQUAL 规则在生成后回填，
 * 见 {@link com.dataforge.derivation.ReconcileEngine}。</p>
 */
@Component
public class GenericExpander {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GenericExpander.class);

    /** 通用假数据源（与 RuleDataGenerator 同款用法）：格式串列走 Faker，不参与 seed 复现 */
    private static final Faker FAKER = new Faker(Locale.CHINA);

    /**
     * 行上下文：该行属于哪个父行、在父行内的序号。
     *
     * <p>配置驱动的派生是"生成 N 行 + 每行独立取值"，本身不携带父子归属信息，
     * 于是既表达不了"每个父行至少 1 条"（随机挂载必然留下空洞），也表达不了
     * "父行内序列"逻辑（如客服会话的 role 交替）。按父行分组派生后，
     * 父引用列可直接取 parentId，列模式也可消费 indexInParent。</p>
     *
     * @param parentId      所属父行主键；无父行感知时为 null
     * @param indexInParent 在父行内的序号（0 起）；无父行感知时为 -1
     * @param parentTable   父表名；无父行感知时为 null
     */
    record RowCtx(Object parentId, int indexInParent, String parentTable) {
        static RowCtx none() {
            return new RowCtx(null, -1, null);
        }
    }

    /** 从生成计划生成派生表（按父行分组派生；无父行时退化为逐行独立生成） */
    public List<DataRow> generate(GenerationPlan.TablePlan plan, TableMeta table,
                                  PipelineContext context,
                                  Map<String, Map<String, List<Object>>> completedData) {
        validateExprColumns(plan, table);   // expr 列预检：只引用同表非 llm 计划列（derive-universal-design.md §H8）
        int target = context.rowCount(table.name());
        Random rng = context.newRandom();
        int seqStart = seqOffset(plan, table, completedData);
        Set<String> plannedCols = plan.columns().stream()
                .map(GenerationPlan.ColumnPlan::column).collect(java.util.stream.Collectors.toSet());
        // 复合唯一键 fk 列：组合级唯一取样（如 cart(user_id,sku_id)/user_role(user_id,role_id)，
        // 随机引用会撞唯一键导致 MySQL 落库拒绝）
        Map<String, List<Object>> uniqueFk = buildUniqueKeyAssignments(plan, table, completedData, target, rng);
        // 按父行分组派生：每父行 rowsPerParent 条（行数精确校准到 target），行内序号供列模式消费
        List<RowCtx> rowContexts = buildRowContexts(plan, completedData, target, rng);
        List<DataRow> rows = new ArrayList<>(target);
        for (int i = 0; i < target; i++) {
            RowCtx rowCtx = rowContexts.get(i);
            Map<String, Object> values = new LinkedHashMap<>();
            // ① 非 expr 计划列先生成（expr 与携带列延后，保证可引用同表已算出的列，§H8 列序）
            for (GenerationPlan.ColumnPlan cp : plan.columns()) {
                if ("expr".equals(cp.mode()) || isCarriedFk(cp)) continue;
                values.put(cp.column(), generateValue(cp, table, context, completedData,
                        i, seqStart, rng, uniqueFk, rowCtx, values));
            }
            // ①′ 携带列 fk（params.follow）：值与同行锚定列取自同一父行，故必须等锚定列算完。
            //    独立成一遍而非依赖 plan 的列书写顺序——让顺序隐式决定正确性太脆。
            for (GenerationPlan.ColumnPlan cp : plan.columns()) {
                if (!isCarriedFk(cp)) continue;
                values.put(cp.column(), generateValue(cp, table, context, completedData,
                        i, seqStart, rng, uniqueFk, rowCtx, values));
            }
            // ② expr 表达式列：引用仅限 ①/①′ 已生成的计划列（validateExprColumns 预检保证）
            for (GenerationPlan.ColumnPlan cp : plan.columns()) {
                if (!"expr".equals(cp.mode())) continue;
                values.put(cp.column(), exprValue(cp, values, table));
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
                                 int index, int seqStart, Random rng,
                                 Map<String, List<Object>> uniqueFk, RowCtx rowCtx,
                                 Map<String, Object> rowValues) {
        return switch (cp.mode()) {
            case "fk" -> {
                // 复合唯一键 fk 列：用预生成的组合级唯一分配（防撞唯一键）
                List<Object> assigned = uniqueFk == null ? null : uniqueFk.get(cp.column());
                if (assigned != null) yield assigned.get(index % assigned.size());
                yield fkValue(cp, completedData, rng, rowCtx, rowValues);
            }
            case "alternate" -> alternateValue(cp, rowCtx);
            case "chain" -> chainValue(cp, rowCtx);
            case "anchorPrice" -> anchorPriceValue(cp, completedData, rng);
            case "seq" -> seqValue(cp, index, seqStart);
            case "weighted" -> weightedValue(cp, rng);
            case "enum" -> enumValue(cp, table, rng);
            case "pool" -> poolValue(cp, rng);
            case "date" -> dateValue(cp, rng, rowCtx);
            case "unique" -> seqValue(cp, index, seqStart);
            case "faker" -> fakerValue(cp, table, rng);
            case "valueRange" -> valueRangeValue(cp, rng, rowValues);
            case "byAncestor" -> byAncestorValue(cp, table, context, completedData, rowValues, rng);
            case "const" -> cp.param("value");
            case "llm" -> null;   // 语义列（商品名/规格等）：ContentGen 展开后 LLM 批量回填
            default -> null;
        };
    }

    /**
     * 复合唯一键 fk 列组合级唯一取样：对表每个唯一键组，涉及的 fk 列从各自父表池
     * 随机组合并去重（保证 MySQL 唯一索引不冲突，如 cart(user_id,sku_id)）。取样不足
     * 时回退随机（允许重复，由校验/修正收敛兜底）。
     */
    private static Map<String, List<Object>> buildUniqueKeyAssignments(
            GenerationPlan.TablePlan plan, TableMeta table,
            Map<String, Map<String, List<Object>>> completedData, int target, Random rng) {
        if (table.uniqueKeys() == null || table.uniqueKeys().isEmpty()) return null;
        Map<String, GenerationPlan.ColumnPlan> cpByCol = plan.columns().stream()
                .collect(java.util.stream.Collectors.toMap(
                        GenerationPlan.ColumnPlan::column, c -> c, (a, b) -> a));
        Map<String, List<Object>> result = new HashMap<>();
        for (String uk : table.uniqueKeys()) {
            String[] colNames = Arrays.stream(uk.split(",")).map(String::trim).toArray(String[]::new);
            // 仅处理唯一键中 plan 标 fk 的列
            List<String> fkCols = Arrays.stream(colNames)
                    .filter(cpByCol::containsKey)
                    .filter(c -> "fk".equals(cpByCol.get(c).mode()))
                    .toList();
            if (fkCols.isEmpty()) continue;
            List<List<Object>> pools = new ArrayList<>();
            for (String c : fkCols) {
                GenerationPlan.ColumnPlan cp = cpByCol.get(c);
                Map<String, List<Object>> parent = completedData.get(cp.param("ref_table"));
                List<Object> pool = parent == null ? List.of() : parent.get(
                        cp.param("ref_column") != null ? cp.param("ref_column") : "id");
                pools.add(pool == null ? List.of() : pool);
            }
            if (pools.stream().anyMatch(List::isEmpty)) continue;
            Map<String, List<Object>> perCol = new HashMap<>();
            for (String c : fkCols) perCol.put(c, new ArrayList<>(target));
            Set<List<Object>> seen = new HashSet<>();
            int filled = 0, attempts = 0;
            while (filled < target && attempts < Math.max(target * 100, 1000)) {
                attempts++;
                List<Object> combo = new ArrayList<>(fkCols.size());
                for (List<Object> pool : pools) combo.add(pool.get(rng.nextInt(pool.size())));
                if (seen.add(combo)) {
                    for (int j = 0; j < fkCols.size(); j++) perCol.get(fkCols.get(j)).add(combo.get(j));
                    filled++;
                }
            }
            if (filled < target) {
                log.warn("唯一键组合取样不足（{}/{}），表 `{}` 剩余行回退随机（修正收敛兜底）",
                        filled, target, table.name());
                for (int i = filled; i < target; i++) {
                    for (int j = 0; j < fkCols.size(); j++) {
                        perCol.get(fkCols.get(j)).add(pools.get(j).get(rng.nextInt(pools.get(j).size())));
                    }
                }
            }
            perCol.forEach((k, v) -> result.put(k, v));
        }
        return result.isEmpty() ? null : result;
    }

    /** FK：从父表（或指定 ref_table）PK 池随机引用（跨现有 + 新增）；zipf=true 走幂律长尾 */
    private Object fkValue(GenerationPlan.ColumnPlan cp,
                           Map<String, Map<String, List<Object>>> completedData, Random rng,
                           RowCtx rowCtx, Map<String, Object> rowValues) {
        String refTable = cp.param("ref_table");
        String refColumn = cp.param("ref_column") != null ? cp.param("ref_column") : "id";
        // 父引用列：直接取该行所属父行的 id。分组派生下父子归属在构造行上下文时已确定，
        // 再随机取样会让"每父行 N 条"的分组与父引用值脱节（分组算出的父行 ≠ 随机挂到的父行）
        if (rowCtx != null && rowCtx.parentId() != null && refTable != null
                && refTable.equals(rowCtx.parentTable()) && "id".equals(refColumn)) {
            return rowCtx.parentId();
        }
        Map<String, List<Object>> parent = completedData.get(refTable);
        if (parent == null) return null;
        // 携带列（params.follow）：值随同行锚定列锁定到同一父行。
        // 如 cart.product_id 必须等于该行 sku_id 所属商品的 id——不锁的话两列各自独立取样，
        // 条目会自相矛盾（购物车挂着"高配版"SKU 却指向另一个商品的商品号）。
        String follow = cp.param("follow");
        if (follow != null && !follow.isBlank()) {
            Object anchor = rowValues == null ? null : rowValues.get(follow);
            List<Object> keys = parent.get("id");
            List<Object> carried = parent.get(refColumn);
            if (anchor != null && keys != null && carried != null) {
                int idx = keys.indexOf(anchor);
                if (idx >= 0 && idx < carried.size()) return carried.get(idx);
            }
            // 锚定列无值 / 父表查不到 → 落到下方随机取样（不抛：锚定列可能本身就是 null 占位）
        }
        List<Object> pool = parent.get(refColumn);
        if (pool == null || pool.isEmpty()) return null;
        // 幂律长尾（修 D6）：少数父值被引用多、多数少——订单在用户间呈长尾分布
        if ("true".equals(cp.param("zipf"))) {
            return pool.get(Zipf.zipfPick(pool.size(), rng));
        }
        return pool.get(rng.nextInt(pool.size()));
    }

    /** 是否为携带列 fk（params.follow 指向同行锚定列）：生成顺序上必须排在锚定列之后 */
    private static boolean isCarriedFk(GenerationPlan.ColumnPlan cp) {
        if (!"fk".equals(cp.mode())) return false;
        String follow = cp.param("follow");
        return follow != null && !follow.isBlank();
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

    /**
     * 序列交替：按父行内序号轮流取值（第 0 条取第一个值）。
     *
     * <p>用于"父行内有序"的列——如客服会话的 role 必须是 user/assistant 交替、
     * 且以 user 开头。无父行上下文时退化为取第一个值。</p>
     */
    private Object alternateValue(GenerationPlan.ColumnPlan cp, RowCtx rowCtx) {
        String values = cp.param("values");
        if (values == null || values.isBlank()) return null;
        String[] pool = values.split(",");
        int idx = rowCtx != null && rowCtx.indexInParent() >= 0
                ? rowCtx.indexInParent() % pool.length : 0;
        return pool[idx].trim();
    }

    /**
     * 构造行上下文：按父行分组派生，每父行 rowsPerParent 条，总数精确校准到 target。
     *
     * <p>无父表 / 父池为空时全部返回 none，退化为逐行独立生成（与改造前行为一致）。</p>
     */
    private List<RowCtx> buildRowContexts(GenerationPlan.TablePlan plan,
                                          Map<String, Map<String, List<Object>>> completedData,
                                          int target, Random rng) {
        List<RowCtx> contexts = new ArrayList<>(target);
        String parentTable = plan.parent();
        List<Object> parentIds = parentTable == null ? null : parentPool(parentTable, completedData);
        if (parentIds == null || parentIds.isEmpty()) {
            for (int i = 0; i < target; i++) contexts.add(RowCtx.none());
            return contexts;
        }
        int[] range = parseRowsPerParent(plan.rowsPerParent());
        int min = range[0];
        int max = Math.max(min, range[1]);
        // 双向安全阀（必须成对，否则死循环）：CountDistribution 的校准循环在两侧都可能收敛不了——
        //   区间下界过高时（parentCount * min > target）减项找不到可减的（全是 min）→ 无限循环；
        //   区间上界过低时（parentCount * max < target）补项永远补不满（全到 max）→ 无限循环。
        // 实测踩中后者：product 目标 300 行而父表 category 仅 24 行，rowsPerParent="2-5"
        // 最多只能派生 120 行，补项循环空转（表现为生成请求永久挂起、无任何日志）。
        // 夹紧方式：以 target/parentCount 的均值为准——rowsPerParent 是"每父行典型值"，
        // 当目标行数与该均值的比值偏离它时，以 target 为准。
        int avgFloor = target / parentIds.size();
        int avgCeil = (int) Math.ceil((double) target / parentIds.size());
        min = Math.max(0, Math.min(min, avgFloor));
        max = Math.max(Math.max(min, max), avgCeil);
        int[] counts = CountDistribution.assign(parentIds.size(), min, max,
                uniformWeights(max - min + 1), target, rng);
        for (int p = 0; p < parentIds.size(); p++) {
            for (int k = 0; k < counts[p]; k++) {
                contexts.add(new RowCtx(parentIds.get(p), k, parentTable));
            }
        }
        // 防御性对齐（父池极小时 CountDistribution 仍可能偏差）
        while (contexts.size() < target) contexts.add(RowCtx.none());
        if (contexts.size() > target) contexts.subList(target, contexts.size()).clear();
        return contexts;
    }

    /** 父表主键池（父引用列的取值来源） */
    private List<Object> parentPool(String parentTable,
                                    Map<String, Map<String, List<Object>>> completedData) {
        Map<String, List<Object>> parent = completedData.get(parentTable);
        return parent == null ? null : parent.get("id");
    }

    /** 解析 rowsPerParent："N" 或 "min-max"；缺省/非法 → [1,1] */
    private static int[] parseRowsPerParent(String rowsPerParent) {
        if (rowsPerParent == null || rowsPerParent.isBlank()) return new int[]{1, 1};
        String s = rowsPerParent.trim();
        try {
            int dash = s.indexOf('-');
            if (dash < 0) {
                int n = Integer.parseInt(s);
                return new int[]{n, n};
            }
            int lo = Integer.parseInt(s.substring(0, dash).trim());
            int hi = Integer.parseInt(s.substring(dash + 1).trim());
            return new int[]{Math.min(lo, hi), Math.max(lo, hi)};
        } catch (NumberFormatException e) {
            return new int[]{1, 1};
        }
    }

    /** 均匀权重（CountDistribution 要求长度 = max-min+1） */
    private static int[] uniformWeights(int len) {
        int[] w = new int[Math.max(1, len)];
        java.util.Arrays.fill(w, 1);
        return w;
    }

    /**
     * 日期：params.range="-90d,0d"（相对今天的天数区间）。
     * {@code params.ordered=true} 时按父行内序号递增——"链"类列（如状态日志）的语义要求
     * 时间维度上也有序，否则按时间排出来的链依然是散的。
     */
    private String dateValue(GenerationPlan.ColumnPlan cp, Random rng, RowCtx rowCtx) {
        String range = cp.param("range");
        int pastDays = 90;
        if (range != null && range.contains(",")) {
            pastDays = Math.abs(parseInt(range.split(",")[0].trim().replace("d", ""), 90));
        }
        if ("true".equals(cp.param("ordered")) && rowCtx != null && rowCtx.indexInParent() >= 0) {
            return ValuePools.orderedDateTime(pastDays, rowCtx.indexInParent(), rng);
        }
        return ValuePools.randomDateTime(pastDays, rng);
    }

    /**
     * 状态链推进：按父行内序号沿 {@code path} 前进——第 i 行取 {@code path[i + offset]}，
     * 越过尾部则停在末尾（<b>不回绕</b>）。
     *
     * <p>用于"跨行必须首尾相接"的列：如 user_status_log 的 from_status/to_status 应构成
     * {@code 1→2→3→3} 这样连续的链。与 {@link #alternateValue} 的区别正在于此——后者取模轮转，
     * 链会绕回开头、接不上（实测：两者独立随机会让每条日志的开头对不上上一条的结尾）。</p>
     *
     * <p>params：{@code path="1,2,3"}、{@code offset="0|1"}（0 取当前节点、1 取下一节点）。
     * 无父行上下文时退化为取 path 首部，与既有模式口径一致。</p>
     */
    private Object chainValue(GenerationPlan.ColumnPlan cp, RowCtx rowCtx) {
        String path = cp.param("path");
        if (path == null || path.isBlank()) return null;
        String[] nodes = path.split(",");
        int offset = parseInt(cp.param("offset"), 0);
        int idx = rowCtx != null && rowCtx.indexInParent() >= 0 ? rowCtx.indexInParent() : 0;
        int pos = Math.min(Math.max(idx + offset, 0), nodes.length - 1);
        String raw = nodes[pos].trim();
        try {
            return Integer.parseInt(raw);   // 状态码等数值列落库为整型，避免依赖隐式转换
        } catch (NumberFormatException e) {
            return raw;                     // 非数值路径（字符串状态）原样返回
        }
    }

    /**
     * 通用值池：显式 {@code type}（name/phone/address/email/remark/logistics）优先；
     * <b>未指定 type 时按列名交给 {@link RuleDataGenerator}</b>——它那套启发式已覆盖
     * images（JSON 数组）/ specs / detail / avatar / id_card 等按列名的识别，
     * 在引擎里再写一份就是两份真相，迟早对不上。
     */
    private Object fakerValue(GenerationPlan.ColumnPlan cp, TableMeta table, Random rng) {
        String type = cp.param("type");
        if (type == null || type.isBlank()) {
            ColumnDef col = table.columns().stream()
                    .filter(c -> c.name().equals(cp.column())).findFirst().orElse(null);
            if (col != null) {
                List<Map<String, Object>> generated =
                        RuleDataGenerator.generateRuleValues(List.of(col), 1, rng);
                if (!generated.isEmpty() && generated.get(0).get(col.name()) != null) {
                    return generated.get(0).get(col.name());
                }
            }
            return ValuePools.randomChineseName(rng);   // 与旧口径（type 缺省=name）一致
        }
        return switch (type) {
            case "phone" -> ValuePools.randomPhone(rng);
            case "address" -> ValuePools.randomAddress(rng);
            case "email" -> FAKER.internet().emailAddress();
            case "remark" -> ValuePools.randomRemark(rng);
            case "logistics" -> ValuePools.randomLogisticsNo(rng);
            default -> ValuePools.randomChineseName(rng);
        };
    }

    /** 取值范围（RuleSetters VALUE_RANGE）：纯数字 min/max 区间随机；表达式（含字母）回退 null 留白 */
    /**
     * 取值范围（RuleSetters VALUE_RANGE）：{@code params.min/max} 既可以是数字字面量，
     * 也可以是<b>同行已生成列的列名</b>（如 {@code min="price"} 表示"不低于售价"）。
     *
     * <p><b>修的是一个静默失效</b>：此前只认字面量，{@code min="price"} 被 parseDouble 判非法后
     * <b>兜底成 0</b>，于是"原价 ≥ 售价"彻底落空、产出原价可能低于售价的数据，全程没有任何提示。</p>
     */
    private Object valueRangeValue(GenerationPlan.ColumnPlan cp, Random rng, Map<String, Object> rowValues) {
        Double min = resolveBound(cp.param("min"), rowValues, cp.column());
        Double max = resolveBound(cp.param("max"), rowValues, cp.column());
        if (min == null && max == null) return null;
        double lo = min != null ? min : 0;
        double hi = max != null ? max : 100000;
        return Math.round((lo + rng.nextDouble() * (hi - lo)) * 100.0) / 100.0;
    }

    /**
     * 祖先列派生（{@code params.keyTable} / {@code specFile}）：沿 plan 的 parent 链上溯到
     * keyTable，取该祖先行的 {@code name} 作键，查规格模板，各维度随机取值拼成 JSON。
     *
     * <p>解决"隔代依赖"：sku 的规格该由祖父 category 决定，而展开阶段手上只有外键。
     * 逐级借 completedData 把本表外键值换算成祖先行 id（本表 fk → 父行 id → 祖父行 id）。</p>
     *
     * <p>任一步断链（无 plan / 无外键 / 祖先无该行 / 模板无该类）→ 返回 null，
     * 由列的可空性兜住：不臆造，也不抛——规格列允许为空。</p>
     */
    private Object byAncestorValue(GenerationPlan.ColumnPlan cp, TableMeta table,
                                   PipelineContext context,
                                   Map<String, Map<String, List<Object>>> completedData,
                                   Map<String, Object> rowValues, Random rng) {
        String keyTable = cp.param("keyTable");
        if (keyTable == null || keyTable.isBlank()) return null;
        Map<String, Map<String, List<String>>> specs = SpecTemplates.load(cp.param("specFile"));
        if (specs.isEmpty()) return null;

        // 本表 → 父 → 祖父（最多两级，覆盖 sku→product→category 这类隔代依赖）
        String parent = parentOf(context, table.name());
        String fk1 = parent == null ? null : fkColumn(context, table.name(), parent);
        if (fk1 == null || rowValues == null) return null;
        Object parentId = rowValues.get(fk1);
        if (parentId == null) return null;

        Object keyRowId;
        if (parent.equals(keyTable)) {
            keyRowId = parentId;                      // 一级即可（如 product 按 category）
        } else {
            String grand = parentOf(context, parent);
            String fk2 = grand == null ? null : fkColumn(context, parent, grand);
            if (fk2 == null || !grand.equals(keyTable)) return null;
            Map<String, List<Object>> pd = completedData.get(parent);
            if (pd == null || pd.get("id") == null || pd.get(fk2) == null) return null;
            int idx = indexOf(pd.get("id"), parentId);
            if (idx < 0 || idx >= pd.get(fk2).size()) return null;
            keyRowId = pd.get(fk2).get(idx);          // 父行 id → 祖先行 id
        }

        Map<String, List<Object>> kd = completedData.get(keyTable);
        if (kd == null || kd.get("id") == null || kd.get("name") == null) return null;
        int k = indexOf(kd.get("id"), keyRowId);
        if (k < 0) return null;
        String label = String.valueOf(kd.get("name").get(k));
        Map<String, List<String>> dims = matchTemplate(specs, label);
        if (dims == null) return null;                // 该分类没有模板 → 落空，不臆造

        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, List<String>> e : dims.entrySet()) {
            List<String> pool = e.getValue();
            if (pool.isEmpty()) continue;
            if (!first) sb.append(',');
            sb.append('"').append(e.getKey()).append("\":\"")
                    .append(pool.get(rng.nextInt(pool.size()))).append('"');
            first = false;
        }
        return sb.append('}').toString();
    }

    /**
     * 模板匹配：先精确命中；否则退化为"分类名包含模板键"。
     *
     * <p>必须退化，因为 {@code category.name} 是 LLM 生成的（"智能手机""女装连衣裙"），
     * 而配置里写的是通用品类词（"手机""女装"）——两者不可能精确相等。
     * 都不中则返回 null：该分类没有模板，落空而不臆造。</p>
     */
    private static Map<String, List<String>> matchTemplate(
            Map<String, Map<String, List<String>>> specs, String label) {
        Map<String, List<String>> exact = specs.get(label);
        if (exact != null) return exact;
        Map<String, List<String>> best = null;
        int bestLen = 0;
        for (Map.Entry<String, Map<String, List<String>>> e : specs.entrySet()) {
            if ("默认".equals(e.getKey())) continue;   // 兜底键只作最后退路，不参与包含匹配
            // 取"匹配上的最长键"，避免"手机数码"被更短的"手机"抢先
            if (label.contains(e.getKey()) && e.getKey().length() > bestLen) {
                best = e.getValue();
                bestLen = e.getKey().length();
            }
        }
        // 分类名由 LLM 生成、无法穷举（实测出现过"电脑办公"这种没有对应键的），
        // 故必须有兜底：否则整个分类的规格会静默落空。
        return best != null ? best : specs.get("默认");
    }

    /** plan 里该表的父表名（无 plan / 无父表返回 null） */
    private static String parentOf(PipelineContext context, String tableName) {
        GenerationPlan.TablePlan tp = context.plan() == null ? null : context.plan().tablePlan(tableName);
        return tp == null ? null : tp.parent();
    }

    /** fromTable 指向 toTable 的外键列名（中间表也要能查，故走运行时元数据） */
    private static String fkColumn(PipelineContext context, String fromTable, String toTable) {
        TableMeta meta = context.table(fromTable);
        if (meta == null) return null;
        return meta.foreignKeys().stream()
                .filter(fk -> toTable.equals(fk.refTable()))
                .map(ForeignKeyDef::column).findFirst().orElse(null);
    }

    private static int indexOf(List<Object> values, Object target) {
        if (values == null) return -1;
        for (int i = 0; i < values.size(); i++) {
            if (target.equals(values.get(i))) return i;
        }
        return -1;
    }

    /**
     * 边界解析：数字字面量直接取；否则按"同行已生成列"取该行的值。
     * 两者都不成立 → 抛异常——不静默兜底，那正是这条配置曾经失效的方式。
     */
    private static Double resolveBound(String raw, Map<String, Object> rowValues, String column) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ignored) {
            // 不是数字 → 按同行列名解析
        }
        Object v = rowValues == null ? null : rowValues.get(s);
        if (v instanceof Number n) return n.doubleValue();
        throw new IllegalStateException("列 `" + column + "` 的 valueRange 边界 `" + raw
                + "` 既不是数字，也不是同行已生成的数值列");
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

    // ---- expr 表达式派生列（derive-universal-design.md §H8） ----

    /** expr 列预检：expr 非空；引用列 ⊆ 同表「非 expr、非 llm」的计划列（违者启动即抛，不静默） */
    private static void validateExprColumns(GenerationPlan.TablePlan plan, TableMeta table) {
        Set<String> allowed = new HashSet<>();
        for (GenerationPlan.ColumnPlan cp : plan.columns()) {
            if (!"expr".equals(cp.mode())) allowed.add(cp.column());
        }
        for (GenerationPlan.ColumnPlan cp : plan.columns()) {
            if ("llm".equals(cp.mode())) allowed.remove(cp.column());   // expr 不得引用 llm 列（生成时为 null）
        }
        for (GenerationPlan.ColumnPlan cp : plan.columns()) {
            if (!"expr".equals(cp.mode())) continue;
            String expr = cp.param("expr");
            if (expr == null || expr.isBlank()) {
                throw new IllegalStateException("表 `" + table.name() + "` 列 `" + cp.column()
                        + "` expr 参数缺失（mode=expr 需 params.expr）");
            }
            Set<String> refs = ExpressionEvaluator.referencedColumns(expr);
            if (!allowed.containsAll(refs)) {
                Set<String> bad = new HashSet<>(refs);
                bad.removeAll(allowed);
                throw new IllegalStateException("表 `" + table.name() + "` 列 `" + cp.column()
                        + "` expr 引用了未先生成/不允许的列 " + bad
                        + "（expr 只能引用同表非 llm 的计划列，§H8）");
            }
        }
    }

    /** expr 列求值：引用列值转 BigDecimal → 表达式计算 → 按列 scale 收口（血缘由 tagAll 统一标 EXPAND） */
    private static Object exprValue(GenerationPlan.ColumnPlan cp, Map<String, Object> values, TableMeta table) {
        String expr = cp.param("expr");
        Set<String> refs = ExpressionEvaluator.referencedColumns(expr);
        Map<String, BigDecimal> rowValues = new HashMap<>();
        for (String col : refs) {
            rowValues.put(col, toBigDecimal(values.get(col), col));
        }
        BigDecimal raw;
        try {
            raw = ExpressionEvaluator.eval(expr, rowValues);
        } catch (ExpressionException e) {
            throw new IllegalStateException("表列 `" + table.name() + "`.`" + cp.column()
                    + "` expr 求值失败（expr: " + expr + "）: " + e.getMessage(), e);
        }
        ColumnDef colDef = table.columns().stream()
                .filter(c -> c.name().equals(cp.column())).findFirst().orElse(null);
        return raw.setScale(exprScale(colDef, cp.param("scale")), RoundingMode.HALF_UP);
    }

    /** 列值 → BigDecimal（null 返回 null，由 ExpressionEvaluator 按"无值"抛；字符串尝试解析数字） */
    private static BigDecimal toBigDecimal(Object v, String column) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                // 落到下方统一抛"非数值"
            }
        }
        throw new ExpressionException("列 `" + column + "` 值非数值，无法参与 expr 计算: " + v);
    }

    /** 目标列收口 scale：params.scale 覆盖 > DECIMAL(M,D) 的 D > 默认 2（§H3） */
    private static int exprScale(ColumnDef col, String override) {
        if (override != null && !override.isBlank()) {
            try {
                return Integer.parseInt(override.trim());
            } catch (NumberFormatException ignored) {
                // 非法覆盖 → 回落类型 scale
            }
        }
        if (col != null && col.type() != null) {
            Matcher m = DECIMAL_SCALE.matcher(col.type().trim());
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return 2;
    }

    /** DECIMAL(M,D) 的 D 提取 */
    private static final Pattern DECIMAL_SCALE =
            Pattern.compile("(?i)decimal\\s*\\(\\s*\\d+\\s*,\\s*(\\d+)\\s*\\)");

    private static DataRow tagAll(Map<String, Object> values, TableMeta table) {
        Map<String, com.dataforge.model.SourceTag> lineage = new LinkedHashMap<>();
        for (ColumnDef col : table.columns()) {
            lineage.put(col.name(), com.dataforge.model.SourceTag.EXPAND);
        }
        return new DataRow(values, lineage);
    }
}
