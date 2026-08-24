package com.dataforge.expansion;

import com.dataforge.model.DataRow;
import com.dataforge.model.GenerationPlan;
import com.dataforge.model.TableMeta;
import com.dataforge.parser.DdlParser;
import com.dataforge.pipeline.FkAlloc;
import com.dataforge.pipeline.PipelineContext;
import com.dataforge.pipeline.PkGen;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批次4 单测（决策 C，修 D4/D6）：真实感原语参数化——
 * fk:zipf 幂律长尾 + anchorPrice 价格锚点派生（配置驱动，不写死电商表名）。
 */
class RealisticPrimitivesTest {

    /** 父表有 price 的自包含 DDL（product 锚点 → sku 价格变体） */
    private static final String DDL = "CREATE TABLE product (\n"
            + "  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '商品ID',\n"
            + "  price DECIMAL(10,2) COMMENT '基准价'\n"
            + ");\n"
            + "CREATE TABLE sku (\n"
            + "  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'SKU ID',\n"
            + "  product_id BIGINT COMMENT '商品ID（引用 product.id）',\n"
            + "  price DECIMAL(10,2) COMMENT '售价'\n"
            + ");";

    @Test
    void derivedPlanHasZipfFkAndAnchorPrice() throws Exception {
        List<TableMeta> tables = new DdlParser().parse(DDL).tables();
        GenerationPlan plan = PlanBuilder.build(tables);

        GenerationPlan.TablePlan sku = plan.tablePlan("sku");
        assertNotNull(sku, "sku 应有生成计划（derived）");
        GenerationPlan.ColumnPlan pid = find(sku, "product_id");
        assertNotNull(pid, "product_id 应有列模式");
        assertEquals("fk", pid.mode());
        assertEquals("true", pid.param("zipf"), "FK 默认 zipf 幂律（修 D6）");
        GenerationPlan.ColumnPlan price = find(sku, "price");
        assertNotNull(price, "price 应有列模式");
        assertEquals("anchorPrice", price.mode(), "父表有 price → 价格锚点（修 D4）");
        assertEquals("product", price.param("ref_table"), "锚点父表应为 product");
    }

    @Test
    void genericExpanderHonorsZipfAndAnchorPrice() throws Exception {
        List<TableMeta> tables = new DdlParser().parse(DDL).tables();
        GenerationPlan plan = PlanBuilder.build(tables);
        GenerationPlan.TablePlan skuPlan = plan.tablePlan("sku");
        TableMeta skuTable = tables.stream().filter(t -> t.name().equals("sku")).findFirst().orElseThrow();

        Map<String, Integer> rows = Map.of("product", 5, "sku", 20);
        PipelineContext ctx = new PipelineContext(tables, rows, 42L);
        PkGen.execute(ctx, PkGen.Strategy.SEQUENTIAL);
        FkAlloc.execute(ctx);

        // 父表已完成数据（锚点池 + 价格池）
        Map<String, Map<String, List<Object>>> completed = new LinkedHashMap<>();
        completed.put("product", Map.of(
                "id", List.of(1L, 2L, 3L, 4L, 5L),
                "price", List.of(100.0, 200.0, 300.0, 400.0, 500.0)));

        GenericExpander expander = new GenericExpander();
        List<DataRow> rows2 = expander.generate(skuPlan, skuTable, ctx, completed);
        assertEquals(20, rows2.size(), "应生成 20 行 SKU");

        Set<Double> allowed = Set.of(80.0, 100.0, 120.0, 160.0, 200.0, 240.0,
                300.0, 320.0, 360.0, 400.0, 480.0, 500.0, 600.0);
        for (DataRow r : rows2) {
            Object pid = r.values().get("product_id");
            assertNotNull(pid, "product_id 不应为空");
            long pv = ((Number) pid).longValue();
            assertTrue(pv >= 1 && pv <= 5, "FK 必须引用父表池: " + pv);
            Object price = r.values().get("price");
            assertNotNull(price, "price 不应为空");
            double dv = ((Number) price).doubleValue();
            assertTrue(allowed.contains(dv),
                    "price 应为锚点价 × 系数(0.8/1.0/1.2): " + dv);
        }
    }

    /** 父表无 price 时不硬配锚点（shop 的 product 无价，sku 价格走其他路径） */
    @Test
    void noAnchorWhenParentHasNoPrice() throws Exception {
        String ddl = "CREATE TABLE book (\n"
                + "  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '书ID',\n"
                + "  title VARCHAR(100) COMMENT '书名'\n"
                + ");\n"
                + "CREATE TABLE borrow (\n"
                + "  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '借阅ID',\n"
                + "  book_id BIGINT COMMENT '书ID（引用 book.id）',\n"
                + "  price DECIMAL(10,2) COMMENT '借阅价'\n"
                + ");";
        List<TableMeta> tables = new DdlParser().parse(ddl).tables();
        GenerationPlan plan = PlanBuilder.build(tables);
        GenerationPlan.ColumnPlan price = find(plan.tablePlan("borrow"), "price");
        assertNotNull(price);
        assertNotEquals("anchorPrice", price.mode(), "父表无 price 列时不配锚点");
    }

    private static GenerationPlan.ColumnPlan find(GenerationPlan.TablePlan tp, String column) {
        return tp.columns().stream().filter(c -> c.column().equals(column)).findFirst().orElse(null);
    }
}
