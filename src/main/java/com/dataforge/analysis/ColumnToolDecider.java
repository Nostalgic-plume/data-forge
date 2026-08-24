package com.dataforge.analysis;

import com.dataforge.expansion.ToolRegistry;
import com.dataforge.llm.DataLlm;
import com.dataforge.model.ColumnDef;
import com.dataforge.model.ColumnToolDecision;
import com.dataforge.model.ForeignKeyDef;
import com.dataforge.model.GenerationPlan;
import com.dataforge.model.TableMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 列工具决策器（工具感知列分类核心）：分析 agent 编排环节——
 * LLM 感知 {@link ToolRegistry} 工具清单，为候选语义列决策"用工具（给参数）还是 LLM"。
 *
 * <p>决策链三层（信任模型红线：LLM 只选工具，执行永远确定性代码）：
 * <ol>
 *   <li><b>铁律层</b>（确定性，不交 LLM）：PK / FK / 显式 ENUM / 日期 → 直接绑工具，不进决策</li>
 *   <li><b>候选层</b>（LLM 决策）：其余语义列（plan 标 llm 或 plan 未覆盖）→ DataLlm.decideColumnTools</li>
 *   <li><b>确定性校验 + 兜底</b>：
 *       tool 名 isKnownTool（防编造）；工具↔结构合法性（fk 必须真实外键、anchorPrice 必须父表有锚点列）；
 *       source 表白名单（RuleDataGenerator 可确定性生成）；校验不过/无决策 → fallback llm</li>
 * </ol>
 * 决策结果写进 {@link GenerationPlan.ColumnPlan#mode()}，source/derived 表统一，生成执行读 plan。</p>
 */
public final class ColumnToolDecider {

    private static final Logger log = LoggerFactory.getLogger(ColumnToolDecider.class);

    /** source 表候选列工具白名单：RuleDataGenerator 能按类型确定性生成的轻量工具 */
    private static final Set<String> SOURCE_TOOL_WHITELIST =
            Set.of("date", "enum", "const", "seq", "valueRange");

    private ColumnToolDecider() {}

    /**
     * 对计划做列工具决策：返回新计划（候选列决策合入 ColumnPlan；无候选/无 LLM 时保持原计划）。
     *
     * @param plan   分析产出的生成计划（PlanBuilder + RuleSetters 之后）
     * @param tables 全部表结构（铁律/结构校验依据）
     * @param llm    LLM 通道（决策失败/空 → 兜底 llm）
     */
    public static GenerationPlan apply(GenerationPlan plan, List<TableMeta> tables, DataLlm llm) {
        if (plan == null || plan.tables().isEmpty()) return plan;
        Map<String, TableMeta> byName = tables == null ? Map.of() : tables.stream()
                .collect(Collectors.toMap(TableMeta::name, t -> t));
        String catalog = ToolRegistry.catalogText();

        List<GenerationPlan.TablePlan> newTables = new ArrayList<>(plan.tables().size());
        for (GenerationPlan.TablePlan tp : plan.tables()) {
            TableMeta meta = byName.get(tp.table());
            if (meta == null) {
                newTables.add(tp);
                continue;
            }
            List<ColumnDef> candidates = candidateColumns(tp, meta);
            if (candidates.isEmpty()) {
                newTables.add(tp);
                continue;
            }
            List<ColumnToolDecision> decisions = decide(llm, tp.table(), candidates, catalog);
            List<GenerationPlan.ColumnPlan> newCols =
                    mergeColumns(tp, meta, candidates, decisions);
            newTables.add(new GenerationPlan.TablePlan(
                    tp.table(), tp.builder(), tp.parent(), tp.rowsPerParent(),
                    newCols, tp.pkStrategy()));
        }
        return new GenerationPlan(newTables);
    }

    // ---- ① 铁律层 + 候选收集 ----

    /** 候选语义列：非 PK/FK/ENUM/日期，且 plan 未配非 llm 工具（plan 标 llm 或 plan 未覆盖） */
    private static List<ColumnDef> candidateColumns(GenerationPlan.TablePlan tp, TableMeta meta) {
        Set<String> fkCols = meta.foreignKeys().stream()
                .map(ForeignKeyDef::column).collect(Collectors.toSet());
        Map<String, GenerationPlan.ColumnPlan> planCols = tp.columns().stream()
                .collect(Collectors.toMap(GenerationPlan.ColumnPlan::column, c -> c, (a, b) -> a));
        List<ColumnDef> candidates = new ArrayList<>();
        for (ColumnDef c : meta.columns()) {
            if (c.primaryKey() || fkCols.contains(c.name())) continue;     // 铁律：PK/FK
            if (!c.enumValues().isEmpty()) continue;                       // 铁律：显式 ENUM → enum 工具
            if (isDateType(c.normalizedType())) continue;                  // 铁律：日期 → date 工具
            GenerationPlan.ColumnPlan cp = planCols.get(c.name());
            if (cp != null && !"llm".equals(cp.mode())) continue;          // 已有非 llm 工具 → 跳过
            candidates.add(c);                                             // 候选：标 llm 或未覆盖
        }
        return candidates;
    }

    // ---- ② LLM 决策（失败 → 空 → 兜底） ----

    private static List<ColumnToolDecision> decide(DataLlm llm, String table,
                                                   List<ColumnDef> candidates, String catalog) {
        if (llm == null) return List.of();
        try {
            return llm.decideColumnTools(table, candidates, catalog);
        } catch (Exception e) {
            log.warn("表 `{}` 列工具决策失败（{}），兜底 llm", table, e.getMessage());
            return List.of();
        }
    }

    // ---- ③ 校验 + 合并 ----

    /** 合并候选列决策进计划；无决策/校验失败 → 默认 llm（安全兜底，不静默造假工具） */
    private static List<GenerationPlan.ColumnPlan> mergeColumns(
            GenerationPlan.TablePlan tp, TableMeta meta,
            List<ColumnDef> candidates, List<ColumnToolDecision> decisions) {
        Map<String, ColumnToolDecision> decisionByCol = decisions.stream()
                .collect(Collectors.toMap(ColumnToolDecision::column, d -> d, (a, b) -> a));
        Map<String, GenerationPlan.ColumnPlan> planCols = new LinkedHashMap<>();
        for (GenerationPlan.ColumnPlan cp : tp.columns()) {
            planCols.put(cp.column(), cp);
        }
        boolean source = "source".equals(tp.builder());
        for (ColumnDef c : candidates) {
            ColumnToolDecision d = decisionByCol.get(c.name());
            GenerationPlan.ColumnPlan decided = d == null ? null
                    : resolveDecision(tp, meta, c, d, source);
            planCols.put(c.name(), decided != null ? decided : GenerationPlan.ColumnPlan.of(c.name(), "llm"));
        }
        return new ArrayList<>(planCols.values());
    }

    /** 单条决策 → 计划列模式；非法（编造工具名/结构不匹配/超白名单）→ null（兜底 llm） */
    private static GenerationPlan.ColumnPlan resolveDecision(GenerationPlan.TablePlan tp, TableMeta meta,
                                                             ColumnDef c, ColumnToolDecision d, boolean source) {
        String tool = d.tool();
        if (tool == null || !ToolRegistry.isKnownTool(tool)) {
            log.warn("表 `{}` 列 `{}` 决策工具名非法 `{}`（不在注册表），拒绝 → llm",
                    tp.table(), c.name(), tool);
            return null;
        }
        if (ToolRegistry.isLlm(tool)) {
            return GenerationPlan.ColumnPlan.of(c.name(), "llm");
        }
        if (source && !SOURCE_TOOL_WHITELIST.contains(tool)) {
            log.warn("表 `{}` 列 `{}` 决策工具 `{}` 不在 source 表白名单，拒绝 → llm",
                    tp.table(), c.name(), tool);
            return null;
        }
        // 工具↔结构合法性校验
        if ("fk".equals(tool) && meta.foreignKeys().stream()
                .noneMatch(f -> f.column().equals(c.name()))) {
            log.warn("表 `{}` 列 `{}` 无真实外键却决策 fk，拒绝 → llm", tp.table(), c.name());
            return null;
        }
        if ("anchorPrice".equals(tool) && tp.parent() == null) {
            log.warn("表 `{}` 列 `{}` 无父表却决策 anchorPrice，拒绝 → llm", tp.table(), c.name());
            return null;
        }
        Map<String, String> params = d.params() == null ? Map.of() : d.params();
        return GenerationPlan.ColumnPlan.of(c.name(), tool, params);
    }

    private static boolean isDateType(String type) {
        if (type == null) return false;
        return type.startsWith("DATE") || type.startsWith("TIME") || type.startsWith("YEAR")
                || "TIMESTAMP".equals(type);
    }
}
