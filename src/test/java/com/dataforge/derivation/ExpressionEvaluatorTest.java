package com.dataforge.derivation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 表达式求值器单测（derive-universal-plan.md T1 验收）。 */
class ExpressionEvaluatorTest {

    // ---- 辅助断言：按数值比较（BigDecimal.equals 含 scale，改用 compareTo） ----

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + "，实际 " + actual);
    }

    // ---- 合法求值 ----

    @Test
    void 常量与简单四则() {
        assertDecimal("3", ExpressionEvaluator.eval("3", Map.of()));
        assertDecimal("13", ExpressionEvaluator.eval("10 + 3", Map.of()));
        assertDecimal("7", ExpressionEvaluator.eval("10 - 3", Map.of()));
        assertDecimal("30", ExpressionEvaluator.eval("10 * 3", Map.of()));
        assertDecimal("4", ExpressionEvaluator.eval("12 / 3", Map.of()));
        assertDecimal("50", ExpressionEvaluator.eval("(100 + 50) / 3 + 0", Map.of()).setScale(0)); // 防除法 scale 干扰，见下行
    }

    @Test
    void 小数加法用BigDecimal不丢失精度() {
        assertDecimal("0.3", ExpressionEvaluator.eval("0.1 + 0.2", Map.of()));
    }

    @Test
    void 列引用与混合运算() {
        Map<String, BigDecimal> row = Map.of("price", new BigDecimal("9.99"), "quantity", new BigDecimal("3"));
        assertDecimal("29.97", ExpressionEvaluator.eval("price * quantity", row));
        assertDecimal("26.97", ExpressionEvaluator.eval("price * quantity - 3", row));
        assertDecimal("59.94", ExpressionEvaluator.eval("(price + price) * quantity", row));
    }

    @Test
    void round收口() {
        Map<String, BigDecimal> row = Map.of("a", new BigDecimal("9.99"), "q", new BigDecimal("3"));
        assertDecimal("29.97", ExpressionEvaluator.eval("round(a * q, 2)", row));
        // 除不尽：1/3 中间 scale=8 → 0.33333333 → round 2 = 0.33
        assertDecimal("0.33", ExpressionEvaluator.eval("round(1 / 3, 2)", Map.of()));
        // 除法精度不提前截断：round(2 / 3, 6) = 0.666667
        assertDecimal("0.666667", ExpressionEvaluator.eval("round(2 / 3, 6)", Map.of()));
    }

    @Test
    void 一元负号() {
        assertDecimal("-5", ExpressionEvaluator.eval("-5", Map.of()));
        assertDecimal("-30", ExpressionEvaluator.eval("-10 * 3", Map.of()));
        Map<String, BigDecimal> row = Map.of("a", new BigDecimal("10"));
        assertDecimal("-20", ExpressionEvaluator.eval("-(a + 10)", row));
    }

    @Test
    void referencedColumns只返回列引用不含round() {
        assertEquals(Set.of("price", "quantity"),
                ExpressionEvaluator.referencedColumns("round(price * quantity, 2)"));
        assertEquals(Set.of("a", "b", "c"),
                ExpressionEvaluator.referencedColumns("a + b * 2 - c"));
    }

    // ---- 非法：显式抛 ExpressionException ----

    @Test
    void 空与语法错误抛异常() {
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.eval("", Map.of()));
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.eval("   ", Map.of()));
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.eval("(a + b", Map.of()));
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.eval("a + + b", Map.of()));
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.eval("a 1", Map.of()));
    }

    @Test
    void 除零抛异常() {
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.eval("5 / 0", Map.of()));
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.eval("5 / (3 - 3)", Map.of()));
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.eval("a / b",
                Map.of("a", BigDecimal.ONE, "b", BigDecimal.ZERO)));
    }

    @Test
    void 引用缺失或无值列抛异常() {
        // 引用了 rowValues 里没有的列
        assertThrows(ExpressionException.class,
                () -> ExpressionEvaluator.eval("price * quantity", Map.of("price", BigDecimal.TEN)));
    }

    @Test
    void 白名单拒绝非白名单函数() {
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.eval("sin(x)", Map.of()));
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.eval("sum(x)", Map.of()));
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.eval("max(a, b)", Map.of()));
    }

    @Test
    void round参数非法抛异常() {
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.eval("round(a)", Map.of("a", BigDecimal.ONE)));          // 缺第二参
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.eval("round(a, 0)", Map.of("a", BigDecimal.ONE)));        // n 越下界
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.eval("round(a, 9)", Map.of("a", BigDecimal.ONE)));        // n 越上界
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.eval("round(a, 2.5)", Map.of("a", BigDecimal.ONE)));      // 非整数
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.eval("round(a, b)", Map.of("a", BigDecimal.ONE, "b", BigDecimal.ONE))); // 非字面量
    }

    @Test
    void 非法字符抛异常() {
        assertThrows(ExpressionException.class, () -> ExpressionEvaluator.eval("a \"b\"", Map.of("a", BigDecimal.ONE)));
    }
}
