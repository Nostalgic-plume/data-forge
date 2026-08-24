package com.dataforge.pipeline;

import com.dataforge.model.GeneratedData;
import com.dataforge.model.TableMeta;

import java.util.*;

/**
 * 跨阶段共享上下文（移植 real-world-data PipelineContext）。
 *
 * <p>PkGen 写入 pkSkeletons → FkAlloc 消费 → 写 fkAllocations → ContentGen 消费 → generatedData。</p>
 *
 * <p>仅管线内部使用（package 内可见性由调用约定保证）。</p>
 */
public final class PipelineContext {

    // ===================== 输入 =====================
    private final List<TableMeta> tables;
    private final Map<String, Integer> rowCounts;
    private final long seed;

    // ===================== PkGen 输出 =====================
    // tableName → { columnName → [value1, value2, ...] }
    private final Map<String, Map<String, List<Object>>> pkSkeletons = new LinkedHashMap<>();

    // ===================== FkAlloc 输出 =====================
    // tableName → { fkColumnName → [perRowValue1, ...] }
    private final Map<String, Map<String, List<Object>>> fkAllocations = new LinkedHashMap<>();

    // ===================== ContentGen 输出 =====================
    private final Map<String, GeneratedData> generatedData = new LinkedHashMap<>();

    // ===================== P5 增量上下文 =====================
    // 每表起始 ID（增量 MAX+1，全量默认 1）——PkGen SEQUENTIAL 起点
    private final Map<String, Long> startIds = new LinkedHashMap<>();
    // 父表现有 PK 池（增量从目标库/staging 读入）——FkAlloc 合并"现有 + 新增"
    private final Map<String, List<Object>> existingPools = new LinkedHashMap<>();

    public PipelineContext(List<TableMeta> tables, Map<String, Integer> rowCounts, long seed) {
        this.tables = tables;
        this.rowCounts = rowCounts;
        this.seed = seed;
    }

    public List<TableMeta> tables() { return tables; }

    public Map<String, Integer> rowCounts() { return rowCounts; }

    public long seed() { return seed; }

    public int rowCount(String tableName) {
        return rowCounts.getOrDefault(tableName, 10);
    }

    public Map<String, Map<String, List<Object>>> pkSkeletons() { return pkSkeletons; }

    public Map<String, Map<String, List<Object>>> fkAllocations() { return fkAllocations; }

    public Map<String, GeneratedData> generatedData() { return generatedData; }

    // ---- P5 增量：起始 ID + 现有池 ----

    /** 每表起始 ID（增量 MAX+1；全量默认 1） */
    public Map<String, Long> startIds() { return startIds; }

    public long startId(String tableName) {
        return startIds.getOrDefault(tableName, 1L);
    }

    /** 父表现有 PK 池（增量从目标库/staging 读入，FkAlloc 合并"现有 + 新增"） */
    public Map<String, List<Object>> existingPools() { return existingPools; }

    public void setStartId(String tableName, long startId) {
        startIds.put(tableName, startId);
    }

    public void setExistingPool(String tableName, List<Object> pkValues) {
        existingPools.put(tableName, pkValues);
    }

    // ===================== A1：逐表 PK 策略提示 =====================
    // SourceScanner @TableId 判定（ASSIGN_ID→SNOWFLAKE / AUTO→SEQUENTIAL），PkGen 消费，fallback 全局
    private final Map<String, String> pkStrategyHints = new LinkedHashMap<>();

    public Map<String, String> pkStrategyHints() {
        return pkStrategyHints;
    }

    public void setPkStrategy(String table, String strategy) {
        pkStrategyHints.put(table, strategy);
    }

    /** 按名查表元数据 */
    public TableMeta table(String name) {
        return tables.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
    }

    /** 确定性随机源（管线各阶段共享，可复现） */
    public Random newRandom() {
        return new Random(seed);
    }
}
