package com.dataforge.analysis;

import com.dataforge.llm.DataLlm;
import com.dataforge.model.ColumnDef;
import com.dataforge.model.ColumnToolDecision;
import com.dataforge.model.GenerationPlan;
import com.dataforge.model.TableMeta;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 列工具决策器单测（工具感知列分类）：
 * <ul>
 *   <li>铁律列（PK/日期）不交 LLM 决策</li>
 *   <li>非法工具名（不在注册表）→ 拒绝 → 兜底 llm</li>
 *   <li>source 表白名单（weighted 超白名单）→ 拒绝 → 兜底 llm</li>
 *   <li>无真实外键决策 fk → 结构校验拒绝 → 兜底 llm</li>
 *   <li>source 表产生列决策（工具 + llm）</li>
 * </ul>
 */
class ColumnToolDeciderTest {

    /** member 表：id(PK) + name(语义) + status(数值枚举) + create_date(日期) */
    private static List<TableMeta> memberTables() {
        return List.of(new TableMeta("member", List.of(
                col("id", "bigint", true),
                col("name", "varchar(50)", false),
                col("status", "tinyint", false),
                col("create_date", "date", false)
        ), List.of(), List.of()));
    }

    private static ColumnDef col(String name, String type, boolean pk) {
        return new ColumnDef(name, type, false, pk, false, "", List.of());
    }

    /** 记录型 LLM：记录被调用的候选列 + 按 toolByColumn 返回决策（未指定 → llm） */
    private static final class StubLlm implements DataLlm {
        final List<String> seenColumns = new ArrayList<>();
        Map<String, String> toolByColumn = Map.of();

        @Override
        public List<ColumnToolDecision> decideColumnTools(String tableName, List<ColumnDef> candidates,
                                                          String toolCatalog) {
            for (ColumnDef c : candidates) seenColumns.add(c.name());
            return candidates.stream()
                    .map(c -> ColumnToolDecision.of(c.name(),
                            toolByColumn.getOrDefault(c.name(), "llm"), Map.of(), 0.8))
                    .toList();
        }

        @Override
        public boolean isMock() {
            return true;
        }

        @Override
        public List<Map<String, Object>> generateRows(String tableName, List<ColumnDef> llmCols,
                                                      int rowCount, String fkContext) {
            return List.of();   // 列工具决策测试不涉及 generateRows
        }
    }

    /** member 的 source 表计划（无列 → 全部候选） */
    private static GenerationPlan sourcePlan() {
        return new GenerationPlan(List.of(
                new GenerationPlan.TablePlan("member", "source", null, "2-5",
                        List.of(), null)));
    }

    /** 铁律列（PK/日期）不进入 LLM 决策 */
    @Test
    void ironRuleColumnsNotPassedToLlm() {
        StubLlm llm = new StubLlm();
        GenerationPlan plan = ColumnToolDecider.apply(sourcePlan(), memberTables(), llm);
        assertNotNull(plan, "决策后应返回计划");

        assertTrue(llm.seenColumns.contains("name"), "name 是候选语义列应进决策");
        assertTrue(llm.seenColumns.contains("status"), "status 数值列应进决策");
        assertFalse(llm.seenColumns.contains("id"), "PK 铁律列不应进 LLM 决策");
        assertFalse(llm.seenColumns.contains("create_date"), "日期铁律列不应进 LLM 决策");
    }

    /** 非法工具名（不在注册表）→ 拒绝 → 兜底 llm */
    @Test
    void unknownToolRejectedAndFallsBackToLlm() {
        StubLlm llm = new StubLlm();
        llm.toolByColumn = Map.of("name", "nonexistent_tool");
        GenerationPlan plan = ColumnToolDecider.apply(sourcePlan(), memberTables(), llm);

        assertEquals("llm", plan.tablePlan("member").columns().stream()
                .filter(c -> c.column().equals("name")).findFirst().orElseThrow().mode(),
                "非法工具名应拒绝并兜底 llm");
    }

    /** source 表白名单：weighted 超白名单 → 拒绝 → 兜底 llm */
    @Test
    void sourceToolWhitelistEnforced() {
        StubLlm llm = new StubLlm();
        llm.toolByColumn = Map.of("status", "weighted");
        GenerationPlan plan = ColumnToolDecider.apply(sourcePlan(), memberTables(), llm);

        assertEquals("llm", plan.tablePlan("member").columns().stream()
                        .filter(c -> c.column().equals("status")).findFirst().orElseThrow().mode(),
                "weighted 不在 source 表白名单应拒绝 → llm");
    }

    /** 无真实外键却决策 fk → 结构校验拒绝 → 兜底 llm */
    @Test
    void fkWithoutRealForeignKeyRejected() {
        StubLlm llm = new StubLlm();
        llm.toolByColumn = Map.of("name", "fk");   // name 不是外键
        GenerationPlan plan = ColumnToolDecider.apply(sourcePlan(), memberTables(), llm);

        assertEquals("llm", plan.tablePlan("member").columns().stream()
                        .filter(c -> c.column().equals("name")).findFirst().orElseThrow().mode(),
                "无真实外键决策 fk 应拒绝 → llm");
    }

    /** source 表产生列决策：工具（valueRange）+ llm 语义列 */
    @Test
    void sourceTableGetsColumnDecisions() {
        StubLlm llm = new StubLlm();
        llm.toolByColumn = Map.of("status", "valueRange");   // 白名单内
        GenerationPlan plan = ColumnToolDecider.apply(sourcePlan(), memberTables(), llm);

        var cols = plan.tablePlan("member").columns();
        assertFalse(cols.isEmpty(), "source 表应产生列决策");
        assertEquals("valueRange", cols.stream()
                .filter(c -> c.column().equals("status")).findFirst().orElseThrow().mode(),
                "valueRange 在 source 白名单应生效");
        assertEquals("llm", cols.stream()
                .filter(c -> c.column().equals("name")).findFirst().orElseThrow().mode(),
                "语义列保持 llm");
    }
}
