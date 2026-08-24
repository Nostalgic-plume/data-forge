package com.dataforge.expansion.blueprint;

import com.dataforge.expansion.ValuePools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 行生成上下文（库无关）——派生器配置的列规则通过它取父表值 / 变体 / 锚点 / 值池。
 *
 * <p>提供的通用能力（跨库复用）：
 * <ul>
 *   <li>{@link #parent}：父表列值（1:N 派生的锚点数据源）</li>
 *   <li>{@link #variantName}/{@link #variantFactor}：变体（规格/版本/尺码）</li>
 *   <li>{@link #anchor}：每父行确定性锚点（锚点派生：父行基础值 × 系数）</li>
 *   <li>{@link #seq}：全局唯一序号（order_no 类唯一键）</li>
 *   <li>{@link #lookup}：跨表查找（从字典表按 code 查 id）</li>
 *   <li>{@link #self}：引用本行已生成列（行内计算：price × quantity）</li>
 *   <li>{@link #cached}：行内共享值（如同一行的省市区三元组）</li>
 *   <li>值池委托：姓名/电话/地址/时间/备注（{@link ValuePools}）</li>
 * </ul>
 */
public final class RowCtx {

    private final int parentIdx;
    private final int variantIdx;
    private final Random rng;
    private final Map<String, Map<String, List<Object>>> completedData;
    private final Map<String, List<Object>> parentColumns;
    private final double[] anchors;
    private final AtomicLong seq;
    private final List<String> variants;
    private final List<Double> variantFactors;
    private final Map<String, Object> row;        // 当前构建行（self 引用）
    private final Map<String, Object> perRowCache = new HashMap<>();

    RowCtx(int parentIdx, int variantIdx, Random rng,
           Map<String, Map<String, List<Object>>> completedData,
           Map<String, List<Object>> parentColumns, double[] anchors, AtomicLong seq,
           Map<String, Object> row, List<String> variants, List<Double> variantFactors) {
        this.parentIdx = parentIdx;
        this.variantIdx = variantIdx;
        this.rng = rng;
        this.completedData = completedData;
        this.parentColumns = parentColumns;
        this.anchors = anchors;
        this.seq = seq;
        this.variants = variants;
        this.variantFactors = variantFactors;
        this.row = row;
    }

    // ===================== 位置信息 =====================

    /** 当前父行下标（0 起） */
    public int parentIdx() {
        return parentIdx;
    }

    /** 当前变体下标（0 起；无变体恒为 0） */
    public int variantIdx() {
        return variantIdx;
    }

    public Random rng() {
        return rng;
    }

    // ===================== 数据源 =====================

    /** 父表某列当前行的值（1:N 派生锚点） */
    public Object parent(String column) {
        List<Object> values = parentColumns.get(column);
        return values != null && parentIdx < values.size() ? values.get(parentIdx) : null;
    }

    /** 父表主键值（便捷方法） */
    public Object parentId() {
        return parent("id");
    }

    /** 本行已生成的列值（行内计算：subtotal = price × quantity） */
    public Object self(String column) {
        return row.get(column);
    }

    /** 从任意已生成表按 key 列值查目标列（字典表映射：role.code=user → role.id） */
    public Object lookup(String table, String targetColumn, String keyColumn, Object keyValue) {
        Map<String, List<Object>> tableData = completedData.get(table);
        if (tableData == null) return null;
        List<Object> keys = tableData.get(keyColumn);
        List<Object> targets = tableData.get(targetColumn);
        if (keys == null || targets == null) return null;
        String target = String.valueOf(keyValue);
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i) != null && keys.get(i).toString().equals(target) && i < targets.size()) {
                return targets.get(i);
            }
        }
        return null;
    }

    /** 行内共享值缓存（同一行多次取同一随机源，如省市区三元组保持一致） */
    @SuppressWarnings("unchecked")
    public <T> T cached(String key, Supplier<T> supplier) {
        return (T) perRowCache.computeIfAbsent(key, k -> supplier.get());
    }

    // ===================== 锚点 / 序号 =====================

    /** 每父行确定性锚点值（锚点派生：父行基础值，跨变体共享） */
    public double anchor() {
        return anchors[parentIdx];
    }

    /** 全局唯一递增序号（order_no 类唯一键） */
    public long seq() {
        return seq.getAndIncrement();
    }

    /** 当前变体名（无变体返回空串） */
    public String variantName() {
        return variants.isEmpty() ? "" : variants.get(variantIdx % variants.size());
    }

    /** 当前变体系数（无变体返回 1.0） */
    public double variantFactor() {
        if (variantFactors.isEmpty()) return 1.0;
        return variantFactors.get(variantIdx % variantFactors.size());
    }

    // ===================== 值池委托（ValuePools） =====================

    public String rndName() {
        return ValuePools.randomChineseName(rng);
    }

    public String rndPhone() {
        return ValuePools.randomPhone(rng);
    }

    public String rndRemark() {
        return ValuePools.randomRemark(rng);
    }

    public String rndLogisticsNo() {
        return ValuePools.randomLogisticsNo(rng);
    }

    public String[] rndRegion() {
        return ValuePools.randomRegion(rng);
    }

    public String rndAddress() {
        return ValuePools.randomAddress(rng);
    }

    public String timePast(int pastDays) {
        return ValuePools.randomDateTime(pastDays, rng);
    }

    public String timeAfter(String baseTime) {
        return ValuePools.laterDateTime(baseTime, rng);
    }

    public int rndInt(int lo, int hi) {
        return lo + rng.nextInt(hi - lo + 1);
    }

    public double rnd(double lo, double hi) {
        return lo + rng.nextDouble() * (hi - lo);
    }

    public boolean chance(double probability) {
        return rng.nextDouble() < probability;
    }

    /** 保留两位小数 */
    public double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** JSON 片段：{"key":"value"} */
    public String json(String key, Object value) {
        return "{\"" + key + "\":\"" + value + "\"}";
    }

    /** picsum 占位图 */
    public String picsum(String seed) {
        return "https://picsum.photos/seed/" + seed + rng.nextInt(100000) + "/400/400";
    }
}
