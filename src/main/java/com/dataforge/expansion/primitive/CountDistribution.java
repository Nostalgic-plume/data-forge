package com.dataforge.expansion.primitive;

import com.dataforge.expansion.Zipf;

import java.util.Random;

/**
 * 计数分布（通用原语）：为 N 个父行分配子行数量（min..max），总行数精确 = target。
 *
 * <p>场景："每个父行有 1-5 个子行，总量必须恰好达标"（订单商品项 / 文档段落 / 套餐包含项）。
 * 权重分布生成后校准：总量不足补项给少行父项、超出减项给多行父项。</p>
 *
 * <pre>
 * int[] perParent = CountDistribution.assign(3000, 1, 5, new int[]{20,35,25,15,5}, 6000, rng);
 * // perParent 之和 == 6000，每项 1-5
 * </pre>
 */
public final class CountDistribution {

    private CountDistribution() {}

    /**
     * @param parentCount 父行数
     * @param min         每父行最小子行数
     * @param max         每父行最大子行数
     * @param weights     数量分布权重（下标 0..max-min）
     * @param target      子行总数（精确达标）
     */
    public static int[] assign(int parentCount, int min, int max, int[] weights, int target, Random rng) {
        int[] counts = new int[parentCount];
        int total = 0;
        double[] w = new double[weights.length];
        for (int i = 0; i < weights.length; i++) w[i] = weights[i];
        for (int i = 0; i < parentCount; i++) {
            counts[i] = min + Zipf.weightedPick(w, rng);
            total += counts[i];
        }
        // 校准到精确 target（补项给 <max 的，减项给 >min 的）
        while (total < target) {
            int idx = rng.nextInt(parentCount);
            if (counts[idx] < max) {
                counts[idx]++;
                total++;
            }
        }
        while (total > target) {
            int idx = rng.nextInt(parentCount);
            if (counts[idx] > min) {
                counts[idx]--;
                total--;
            }
        }
        return counts;
    }
}
