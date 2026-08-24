package com.dataforge.expansion;

import java.util.List;

/**
 * 展开器配置：规格蓝图 + 分布权重（P1 内置电商默认值，P6 分析 agent 产 project_rules 后替换）。
 *
 * @param skuBlueprints  规格蓝图（SKU 规格名 + 价格系数；锚点价 × 系数 = 各 SKU 售价）
 * @param orderItemWeights 订单商品项数量分布权重（下标 0-4 对应 1-5 项）
 * @param statusWeights  订单状态权重（下标 0-6 对应 0待支付..6已退款；绝大多数为 4 已完成）
 */
public record ExpansionConfig(
        List<SpecBlueprint> skuBlueprints,
        int[] orderItemWeights,
        double[] statusWeights
) {
    public record SpecBlueprint(String name, double priceFactor) {
    }

    /** shop 电商默认蓝图（P6 分析 agent 未上线前的内置值） */
    public static ExpansionConfig defaultShop() {
        return new ExpansionConfig(
                List.of(
                        new SpecBlueprint("标准版", 1.0),
                        new SpecBlueprint("高配版", 1.2),
                        new SpecBlueprint("顶配版", 1.4),
                        new SpecBlueprint("至尊版", 1.8)),
                new int[]{20, 35, 25, 15, 5},     // 1-5 项：平均 ≈2.5
                new double[]{0.07, 0.10, 0.08, 0.05, 0.65, 0.04, 0.01}); // 0-6 状态
    }
}
