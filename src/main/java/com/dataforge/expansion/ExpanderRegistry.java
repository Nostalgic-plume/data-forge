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
 * <p><b>库无关性证明</b>：4 张表（sku/address/user_role/shop）用声明式配置
 * {@link DerivedTableConfig} 驱动，换库只需新写配置；3 张表（orders/order_item/cart）
 * 因特殊逻辑保留 Java 派生器：
 * <ul>
 *   <li>orders：状态机 + 状态-时间线联动（声明式难表达时序依赖）</li>
 *   <li>order_item：总行数精确达标 + 多表快照回填</li>
 *   <li>cart：随机组合 + 唯一约束去重（非均匀派生）</li>
 * </ul></p>
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
