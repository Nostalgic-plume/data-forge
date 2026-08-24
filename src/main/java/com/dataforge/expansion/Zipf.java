package com.dataforge.expansion;

import java.util.Random;

/**
 * Zipf 幂律分配（P1 真实感）：少数父值被引用多，多数父值被引用少。
 * 权重 w(i) = 1/(i+1)，归一化轮盘采样——订单在用户间呈长尾分布。
 */
public final class Zipf {

    private Zipf() {}

    /** 按 Zipf 权重从 0..n-1 采样一个下标（rank 0 概率最高） */
    public static int zipfPick(int n, Random rng) {
        if (n <= 1) return 0;
        double sum = 0;
        for (int i = 0; i < n; i++) sum += 1.0 / (i + 1);
        double r = rng.nextDouble() * sum;
        double acc = 0;
        for (int i = 0; i < n; i++) {
            acc += 1.0 / (i + 1);
            if (r < acc) return i;
        }
        return n - 1;
    }

    /** 加权采样：weights 归一化轮盘，返回命中下标 */
    public static int weightedPick(double[] weights, Random rng) {
        double sum = 0;
        for (double w : weights) sum += w;
        double r = rng.nextDouble() * sum;
        double acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += weights[i];
            if (r < acc) return i;
        }
        return weights.length - 1;
    }
}
