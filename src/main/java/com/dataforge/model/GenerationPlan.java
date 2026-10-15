package com.dataforge.model;

import java.util.List;
import java.util.Map;

/**
 * 生成计划（通用化核心）：分析 agent 从表结构产出的"怎么生成每张表"的声明式配置，
 * 由通用执行引擎（GenericExpander）执行——换任何业务域只改配置，零 Java 改动。
 *
 * <p>表分类（builder）：
 * <ul>
 *   <li><b>source</b>：语义源表（LLM 生成样本，如 member/book）——语义含量高</li>
 *   <li><b>derived</b>：派生表（零 LLM 规则生成，如 borrow，按父表行数派生）</li>
 *   <li><b>fixed</b>：固定枚举表（内置种子，如角色/类型）</li>
 * </ul>
 * 列生成模式（mode，见 {@link ColumnPlan}）。</p>
 */
public record GenerationPlan(
        List<TablePlan> tables
) {
    /** 空计划（无分析产物，fallback 内置展开器） */
    public static GenerationPlan empty() {
        return new GenerationPlan(List.of());
    }

    /** 某表的生成计划（无则 null → 用内置模板） */
    public TablePlan tablePlan(String tableName) {
        for (TablePlan t : tables) {
            if (t.table().equals(tableName)) return t;
        }
        return null;
    }

    public boolean hasTable(String tableName) {
        return tablePlan(tableName) != null;
    }

    /** 单表生成计划：builder 分类 + 派生关系 + 列生成方式 + PK 策略提示 */
    public record TablePlan(
            String table,
            String builder,           // source / derived / fixed
            String parent,            // derived 的父表（可为空）
            String rowsPerParent,     // 每父行数："N" 或 "min-max"
            List<ColumnPlan> columns,
            String pkStrategy         // PK 策略提示（@TableId 判定，SEQUENTIAL/SNOWFLAKE，可 null）→ PkGen 消费
    ) {
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isDerived() {
            return "derived".equals(builder);
        }

        /** 固定种子表：值与 id 由内置样本/本地数据文件打表，零 LLM 零随机（如 role/admin/category） */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isFixed() {
            return "fixed".equals(builder);
        }
    }

    /**
     * 列生成模式（mode）：
     * <ul>
     *   <li><b>fk</b>：外键（params: ref_table/ref_column，默认父表 id）</li>
     *   <li><b>seq</b>：序号（params: prefix/width；自动续号不冲突）</li>
     *   <li><b>weighted</b>：权重分布（params: weights="4:0.85,0:0.05"）</li>
     *   <li><b>enum</b>：枚举值随机（取列枚举值，无需 params）</li>
     *   <li><b>pool</b>：值池随机（params: values="A,B,C"）</li>
     *   <li><b>date</b>：日期范围（params: range="-90d,0d"）</li>
     *   <li><b>unique</b>：与已填值唯一（params: 无，前缀自增）</li>
     *   <li><b>faker</b>：通用值生成（params: type=name/phone/address/email/remark/amount...）</li>
     *   <li><b>const</b>：常量（params: value）</li>
     * </ul>
     */
    public record ColumnPlan(
            String column,
            String mode,
            Map<String, String> params
    ) {
        public static ColumnPlan of(String column, String mode, Map<String, String> params) {
            return new ColumnPlan(column, mode, params == null ? Map.of() : params);
        }

        public static ColumnPlan of(String column, String mode) {
            return new ColumnPlan(column, mode, Map.of());
        }

        public String param(String key) {
            return params().get(key);
        }
    }
}
