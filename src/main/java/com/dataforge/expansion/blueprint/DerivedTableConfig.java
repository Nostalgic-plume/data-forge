package com.dataforge.expansion.blueprint;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 派生表声明式配置（库无关的"行构建器"雏形，P6 编译 YAML 到同一结构）。
 *
 * <p>声明一张"从父表 1:N 派生"的表：每父行派生出 rowsPerParent 行变体，
 * 每列的值由 {@link ColumnRule}（函数式规则，读 {@link RowCtx}）产生。</p>
 *
 * <pre>
 * 示例（换库只需新写配置，无需 Java 派生器类）：
 * DerivedTableConfig.of("sku")
 *     .parent("product", 4)
 *     .variants(List.of("标准版","高配版"), List.of(1.0, 1.2))
 *     .column("product_id", RowCtx::parentId)
 *     .column("price", c -> c.round2(c.anchor() * c.variantFactor()));
 * </pre>
 */
public final class DerivedTableConfig {

    /** 列规则：读行上下文产生该列值（null = 不写入该列） */
    @FunctionalInterface
    public interface ColumnRule {
        Object apply(RowCtx ctx);
    }

    public final String table;
    public String parentTable;
    public int rowsPerParent = 1;
    public int maxParents = Integer.MAX_VALUE;   // 只取前 N 个父行（固定前缀派生）
    public double anchorMin = 0;
    public double anchorMax = 0;                 // 0 = 不启用锚点
    public List<String> variants = List.of();
    public List<Double> variantFactors = List.of();
    public final LinkedHashMap<String, ColumnRule> columns = new LinkedHashMap<>();

    private DerivedTableConfig(String table) {
        this.table = table;
    }

    public static DerivedTableConfig of(String table) {
        return new DerivedTableConfig(table);
    }

    /** 派生源：父表 + 每父行派生行数 */
    public DerivedTableConfig parent(String parentTable, int rowsPerParent) {
        this.parentTable = parentTable;
        this.rowsPerParent = rowsPerParent;
        return this;
    }

    /** 只取前 N 个父行派生（固定前缀账号：shop 取前 5 个 user） */
    public DerivedTableConfig maxParents(int maxParents) {
        this.maxParents = maxParents;
        return this;
    }

    /** 锚点范围（每父行确定性派生一个锚点值，变体共享） */
    public DerivedTableConfig anchorRange(double min, double max) {
        this.anchorMin = min;
        this.anchorMax = max;
        return this;
    }

    /** 变体（规格/版本）：名称列表 + 对应系数列表（下标对齐） */
    public DerivedTableConfig variants(List<String> names, List<Double> factors) {
        this.variants = names;
        this.variantFactors = factors;
        return this;
    }

    /** 声明一列的值规则（按声明顺序生成，规则可 {@link RowCtx#self} 引用已生成列） */
    public DerivedTableConfig column(String name, ColumnRule rule) {
        this.columns.put(name, rule);
        return this;
    }
}
