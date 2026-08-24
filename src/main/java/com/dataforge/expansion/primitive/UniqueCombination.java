package com.dataforge.expansion.primitive;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 唯一组合采样（通用原语）：从两个父表随机组合 target 对下标，满足唯一约束（去重重抽）。
 *
 * <p>场景："随机关联两个实体，同一组合只能出现一次"（购物车 user×sku / 点赞 user×post /
 * 关注 user×author 等有唯一约束的关系表）。</p>
 *
 * <pre>
 * List&lt;long[]&gt; pairs = UniqueCombination.sample(userCount, skuCount, 801, seen, rng);
 * // 每对 = [userIdx, skuIdx]，组合唯一（seen 可跨批共享去重）
 * </pre>
 */
public final class UniqueCombination {

    private UniqueCombination() {}

    /**
     * @param aCount 源表 A 行数
     * @param bCount 源表 B 行数
     * @param target 目标组合数
     * @param seen   已用组合集合（"a:b"），可预置实现跨批去重
     * @return 下标对列表 [aIdx, bIdx]，长度 ≤ target（组合空间不足时提前结束）
     */
    public static List<long[]> sample(int aCount, int bCount, int target, Set<String> seen, Random rng) {
        int maxPairs = aCount * bCount;
        int cap = Math.min(target, maxPairs);
        List<long[]> result = new ArrayList<>(cap);
        int attempts = 0;
        while (result.size() < cap && attempts < cap * 20) {
            attempts++;
            int a = rng.nextInt(aCount);
            int b = rng.nextInt(bCount);
            if (!seen.add(a + ":" + b)) continue; // 唯一约束去重
            result.add(new long[]{a, b});
        }
        return result;
    }

    /** 空去重集合（独立组合） */
    public static Set<String> newSeen() {
        return new HashSet<>();
    }
}
