package com.dataforge.expansion.primitive;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * 状态-事件时间线（通用原语）：状态机驱动的字段联动。
 *
 * <p>任意"状态 → 触发字段值"的场景（订单状态机 / 工单流转 / 审批流）：
 * 声明每个状态触发的事件字段序列，时间值按事件顺序递增推进。</p>
 *
 * <pre>
 * StatusTimeline.Timeline tl = StatusTimeline.build(status, createTime,
 *     List.of(
 *         Step.when(s -> s >= 1 && s != 5, "pay_time",  (t, r) -> ValuePools.laterDateTime(t, r)),
 *         Step.when(s -> s >= 2,            "deliver_time", (t, r) -> ValuePools.laterDateTime(t, r))),
 *     rng);
 * </pre>
 */
public final class StatusTimeline {

    private StatusTimeline() {}

    /** 时间线结果：触发的事件字段值 + 最后一个事件时间 */
    public record Timeline(Map<String, Object> fields, String lastEvent) {
    }

    /** 一个联动步骤：状态满足条件 → 给字段赋值（值由时间游标 + rng 生成） */
    public record Step(Predicate<Integer> condition, String field,
                       BiFunction<String, Random, Object> value) {
        public static Step when(Predicate<Integer> condition, String field,
                                BiFunction<String, Random, Object> value) {
            return new Step(condition, field, value);
        }
    }

    /**
     * 构建时间线：按步骤顺序，满足条件则生成字段值，并把时间游标推进到该事件。
     *
     * @param status     当前状态值
     * @param createTime 起始时间（create_time）
     * @param steps      按业务时序排列的联动步骤（pay → deliver → receive）
     * @return 触发字段 + 最后事件时间
     */
    public static Timeline build(int status, String createTime, List<Step> steps, Random rng) {
        String cursor = createTime;
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Step step : steps) {
            if (step.condition.test(status)) {
                Object value = step.value.apply(cursor, rng);
                if (value != null) {
                    fields.put(step.field, value);
                    if (value instanceof String s) cursor = s; // 时间游标推进到该事件
                }
            }
        }
        return new Timeline(fields, cursor);
    }
}
