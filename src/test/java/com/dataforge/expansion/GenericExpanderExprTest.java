package com.dataforge.expansion;

import com.dataforge.model.ColumnDef;
import com.dataforge.model.DataRow;
import com.dataforge.model.GenerationPlan;
import com.dataforge.model.SourceTag;
import com.dataforge.model.TableMeta;
import com.dataforge.pipeline.PipelineContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GenericExpander expr 派生列模式单测（derive-universal-plan.md T2 验收）。 */
class GenericExpanderExprTest {

    // ---- 夹具：最小派生表（qty 随机、price 常量、subtotal 由 expr 派生） ----

    private static ColumnDef col(String name, String type, boolean nullable) {
        return new ColumnDef(name, type, nullable, false, false, "", List.of());
    }

    private static TableMeta table() {
        return new TableMeta("line_item",
                List.of(col("qty", "INT", false),
                        col("price", "DECIMAL(10,2)", false),
                        col("subtotal", "DECIMAL(10,2)", false)),
                List.of(), List.of());
    }

    private static GenerationPlan.TablePlan plan(Map<String, Map<String, String>> colParams) {
        Map<String, Map<String, String>> p = new LinkedHashMap<>(colParams);
        List<GenerationPlan.ColumnPlan> cols = List.of(
                GenerationPlan.ColumnPlan.of("qty", "valueRange", p.getOrDefault("qty", Map.of("min", "1", "max", "9"))),
                GenerationPlan.ColumnPlan.of("price", "const", Map.of("value", "2.5")),
                GenerationPlan.ColumnPlan.of("subtotal", "expr", p.getOrDefault("subtotal", Map.of("expr", "round(price * qty, 2)")))
        );
        return new GenerationPlan.TablePlan("line_item", "derived", null, null, cols, null);
    }

    private static PipelineContext context() {
        return new PipelineContext(List.of(table()), Map.of("line_item", 20), 42L);
    }

    @Test
    void expr列按同表列计算且血缘EXPAND() {
        TableMeta table = table();
        List<DataRow> rows = new GenericExpander().generate(plan(Map.of()), table, context(), Map.of());
        assertEquals(20, rows.size());
        for (DataRow row : rows) {
            double qty = ((Number) row.values().get("qty")).doubleValue();
            BigDecimal expected = BigDecimal.valueOf(2.5 * qty).setScale(2, RoundingMode.HALF_UP);
            BigDecimal subtotal = (BigDecimal) row.values().get("subtotal");
            assertEquals(0, expected.compareTo(subtotal),
                    "subtotal 应 = price(" + row.values().get("price") + ") × qty(" + qty + ")");
            assertEquals(SourceTag.EXPAND, row.lineage().get("subtotal"));
        }
    }

    @Test
    void expr确定性同seed可复现() {
        GenericExpander g = new GenericExpander();
        List<DataRow> a = g.generate(plan(Map.of()), table(), context(), Map.of());
        List<DataRow> b = g.generate(plan(Map.of()), table(), context(), Map.of());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(((Number) a.get(i).values().get("qty")).doubleValue(),
                    ((Number) b.get(i).values().get("qty")).doubleValue());
            assertEquals(0, ((BigDecimal) a.get(i).values().get("subtotal"))
                    .compareTo((BigDecimal) b.get(i).values().get("subtotal")));
        }
    }

    @Test
    void expr参数缺失_启动即抛() {
        TablePlanMissing missing = new TablePlanMissing();
        assertThrows(IllegalStateException.class,
                () -> new GenericExpander().generate(missing.plan(), table(), context(), Map.of()));
    }

    @Test
    void expr引用不存在列_预检抛() {
        TableMeta table = table();
        GenerationPlan.TablePlan bad = plan(Map.of("subtotal", Map.of("expr", "price * no_such")));
        assertThrows(IllegalStateException.class,
                () -> new GenericExpander().generate(bad, table, context(), Map.of()));
    }

    @Test
    void expr引用expr列_预检抛() {
        // subtotal 引用同为 expr 的 another_expr（链式 expr V1 不支持，§H8）
        Map<String, Map<String, String>> p = Map.of("subtotal", Map.of("expr", "another_expr * 2"));
        List<GenerationPlan.ColumnPlan> cols = List.of(
                GenerationPlan.ColumnPlan.of("qty", "valueRange", Map.of("min", "1", "max", "9")),
                GenerationPlan.ColumnPlan.of("another_expr", "expr", Map.of("expr", "qty + 1")),
                GenerationPlan.ColumnPlan.of("subtotal", "expr", Map.of("expr", "another_expr * 2")));
        GenerationPlan.TablePlan plan = new GenerationPlan.TablePlan("line_item", "derived", null, null, cols, null);
        assertThrows(IllegalStateException.class,
                () -> new GenericExpander().generate(plan, table(), context(), Map.of()));
    }

    @Test
    void expr求值运行期错误_除零抛IllegalStateException() {
        // price / zero：zero 恒为 0 → 每行都除零 → 显式失败（§H4 不静默填 0）
        List<ColumnDef> cols = List.of(
                col("price", "DECIMAL(10,2)", false),
                col("zero", "DECIMAL(10,2)", false),
                col("bad", "DECIMAL(10,2)", false));
        TableMeta table = new TableMeta("line_item", cols, List.of(), List.of());
        List<GenerationPlan.ColumnPlan> planCols = List.of(
                GenerationPlan.ColumnPlan.of("price", "const", Map.of("value", "2.5")),
                GenerationPlan.ColumnPlan.of("zero", "const", Map.of("value", "0")),
                GenerationPlan.ColumnPlan.of("bad", "expr", Map.of("expr", "price / zero")));
        GenerationPlan.TablePlan plan =
                new GenerationPlan.TablePlan("line_item", "derived", null, null, planCols, null);
        PipelineContext ctx = new PipelineContext(List.of(table), Map.of("line_item", 3), 42L);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new GenericExpander().generate(plan, table, ctx, Map.of()));
        assertTrue(ex.getMessage().contains("求值失败"), "错误信息应带表达式上下文: " + ex.getMessage());
    }

    // ---- 私有夹具：expr 参数缺失的计划 ----
    private record TablePlanMissing() {
        GenerationPlan.TablePlan plan() {
            List<GenerationPlan.ColumnPlan> cols = List.of(
                    GenerationPlan.ColumnPlan.of("qty", "valueRange", Map.of("min", "1", "max", "9")),
                    GenerationPlan.ColumnPlan.of("price", "const", Map.of("value", "2.5")),
                    GenerationPlan.ColumnPlan.of("subtotal", "expr", Map.of()));   // 缺 params.expr
            return new GenerationPlan.TablePlan("line_item", "derived", null, null, cols, null);
        }
    }
}
