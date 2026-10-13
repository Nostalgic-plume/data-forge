package com.dataforge.expansion;

import com.dataforge.expansion.blueprint.DerivedTableConfig;
import com.dataforge.expansion.blueprint.DerivedTableExpander;
import com.dataforge.expansion.blueprint.RowCtx;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 派生器注册表（组装全部 Expander）。
 *
 * <p><b>定位：无计划时的内置 fallback 模板</b>。正常运行的生成由落盘的
 * {@code generation-plan.json} 驱动（{@link ExpansionEngine} 判定为计划优先），
 * 这里注册的实现只在计划缺失、且现场分析也没产出计划时兜底。</p>
 *
 * <p><b>实际生效面（2026-10-13 复核）</b>：shop 库的计划已覆盖
 * sku/address/user_role/cart（builder=derived）与 shop（builder=source），
 * 这 5 张表在常态下<b>不会</b>走到本表的任何实现；真正由本表生成的只有
 * orders / order_item（它们不在计划内）：
 * <ul>
 *   <li>orders：状态机 + 状态-时间线联动（声明式难表达时序依赖）</li>
 *   <li>order_item：总行数精确达标 + 多表快照回填</li>
 * </ul>
 * 其中 cart 曾以"随机组合 + 唯一约束去重"为由保留 Java 实现，其约束在计划驱动下
 * 已由 {@code GenericExpander} 的复合唯一键取样承担（另见携带列 follow：修复
 * product_id 与 sku_id 的从属关系）。其余条目属历史遗留，待步骤 5
 * （引擎代码去业务字面量）统一清理。</p>
 */
@Configuration
public class ExpanderRegistry {

    @Bean
    List<Expander> expanders() {
        return List.of(
                new DerivedTableExpander(skuConfig()),
                new DerivedTableExpander(addressConfig()),
                new DerivedTableExpander(userRoleConfig()),
                new DerivedTableExpander(shopConfig()),
                new OrdersExpander(),
                new OrderItemExpander(),
                new CartExpander());
    }

    // =============================================================
    //  shop 电商蓝图（声明式配置；P6 分析产物编译到同一结构）
    // =============================================================

    private static final String[] SHOP_NAMES = {"老王数码城", "小李家电城", "小张手机城", "老陈数码港", "小刘数码店"};

    /** SKU：product × 4 规格，锚点价 × 规格系数（锚点派生核心示例） */
    public static DerivedTableConfig skuConfig() {
        return DerivedTableConfig.of("sku")
                .parent("product", 4)
                .variants(List.of("标准版", "高配版", "顶配版", "至尊版"),
                        List.of(1.0, 1.2, 1.4, 1.8))
                .anchorRange(99, 9901)
                .column("product_id", RowCtx::parentId)
                .column("name", c -> String.valueOf(c.parent("name")) + " " + c.variantName())
                .column("specs", c -> c.json("规格", c.variantName()))
                .column("price", c -> c.round2(c.anchor() * c.variantFactor()))
                .column("original_price",
                        c -> c.round2(((Number) c.self("price")).doubleValue() * (1.2 + c.rnd(0, 1.8))))
                .column("stock", c -> c.chance(0.1) ? 0 : c.rndInt(0, 999))
                .column("image", c -> c.picsum("sku"))
                .column("create_time", c -> c.timePast(90))
                .column("update_time", c -> c.timeAfter((String) c.self("create_time")))
                .column("deleted", c -> c.chance(0.1) ? 1 : 0);
    }

    /** 收货地址：user × 2，省市区三元组行内共享，每用户首条默认 */
    public static DerivedTableConfig addressConfig() {
        return DerivedTableConfig.of("address")
                .parent("user", 2)
                .column("user_id", RowCtx::parentId)
                .column("receiver_name", RowCtx::rndName)
                .column("receiver_phone", RowCtx::rndPhone)
                .column("province", c -> c.cached("region", () -> ValuePools.randomRegion(c.rng()))[0])
                .column("city", c -> c.cached("region", () -> ValuePools.randomRegion(c.rng()))[1])
                .column("district", c -> c.cached("region", () -> ValuePools.randomRegion(c.rng()))[2])
                .column("detail", c -> "第" + c.rndInt(1, 30) + "栋" + c.rndInt(1, 20) + "0" + c.rndInt(0, 10) + "室")
                .column("is_default", c -> c.variantIdx() == 0 ? 1 : 0)
                .column("create_time", c -> c.timePast(120))
                .column("update_time", c -> c.timeAfter((String) c.self("create_time")))
                .column("deleted", c -> c.chance(0.1) ? 1 : 0);
    }

    /** 用户-角色：每 user 一条，角色从 role 字典表按 code=user 查 id */
    public static DerivedTableConfig userRoleConfig() {
        return DerivedTableConfig.of("user_role")
                .parent("user", 1)
                .column("user_id", RowCtx::parentId)
                .column("role_id", c -> c.lookup("role", "id", "code", "user"))
                .column("create_time", c -> c.timePast(180));
    }

    /** 店铺：前 5 个 user 派生商家账号（merchant1-5） */
    public static DerivedTableConfig shopConfig() {
        return DerivedTableConfig.of("shop")
                .parent("user", 1)
                .maxParents(5)
                .column("user_id", RowCtx::parentId)
                .column("username", c -> "merchant" + (c.parentIdx() + 1))
                .column("password", c -> "$2a$10$" + "DataForgePlaceholderHash")
                .column("shop_name", c -> SHOP_NAMES[c.parentIdx()])
                .column("shop_logo", c -> c.picsum("shop"))
                .column("shop_desc", c -> "专注优质数码产品，正品保障，售后无忧")
                .column("contact_name", RowCtx::rndName)
                .column("contact_phone", RowCtx::rndPhone)
                .column("status", c -> 0)
                .column("create_time", c -> c.timePast(180))
                .column("update_time", c -> c.timeAfter((String) c.self("create_time")))
                .column("deleted", c -> 0);
    }
}
