package com.dataforge.derivation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 派生列表达式求值器（无语义纯计算器，derive-universal-design.md §H1/H2）。
 *
 * <p>白名单语法：数字常量 / 列引用（本表数值列）/ 一元负号 / {@code + - * /} / 括号 /
 * {@code round(x, n)}（n 为字面量整数，范围 {@link #ROUND_MIN}~{@link #ROUND_MAX}）。
 * 实现为手写递归下降解析（不 eval、不反射、不拼 SQL），AST 求值全程 {@link BigDecimal}。</p>
 *
 * <p>显式失败（§H4）：语法错、未知/无值列、除零、round 参数非法 → 抛 {@link ExpressionException}。</p>
 *
 * <p>精度（§H3）：除法取 max(操作数 scale, {@link #DIV_SCALE_FLOOR}) + HALF_UP 防过早截断；
 * 无 round 的裸表达式不在此收口，由调用方按列 scale 统一收口。</p>
 */
public final class ExpressionEvaluator {

    private ExpressionEvaluator() {}

    /** 除法的中间 scale 下限（防 1/3 这类无限小数过早截断，最终由调用方按列 scale 收口） */
    public static final int DIV_SCALE_FLOOR = 8;

    /** round(x, n) 允许的 n 范围 */
    public static final int ROUND_MIN = 1;
    public static final int ROUND_MAX = 6;

    /** 解析表达式并收集引用的列名集合（供生成侧做存在性/顺序预检） */
    public static Set<String> referencedColumns(String expr) {
        return new Parser(expr).parse().collect(new HashSet<>());
    }

    /**
     * 求值：表达式 + 本行已生成列值。
     *
     * @param rowValues 列名 → 本行已生成值；引用的列必须存在且非 null
     * @throws ExpressionException 语法错 / 引用列缺失或无值 / 除零 / round 参数非法
     */
    public static BigDecimal eval(String expr, Map<String, BigDecimal> rowValues) {
        if (rowValues == null) {
            throw new ExpressionException("rowValues 不能为 null");
        }
        return new Parser(expr).parse().eval(rowValues);
    }

    // ---- AST ----

    /** 表达式 AST（密封：求值/取引用均递归） */
    private sealed interface ExprNode permits NumNode, RefNode, NegNode, BinNode, RoundNode {
        BigDecimal eval(Map<String, BigDecimal> rowValues);

        default Set<String> collect(Set<String> out) {
            return out;
        }
    }

    /** 数字常量 */
    private record NumNode(BigDecimal value) implements ExprNode {
        @Override
        public BigDecimal eval(Map<String, BigDecimal> rowValues) {
            return value;
        }
    }

    /** 列引用（rowValues 取值） */
    private record RefNode(String column) implements ExprNode {
        @Override
        public BigDecimal eval(Map<String, BigDecimal> rowValues) {
            BigDecimal v = rowValues.get(column);
            if (v == null) {
                throw new ExpressionException("表达式引用列 `" + column + "` 无值或不存在（不可参与计算）");
            }
            return v;
        }

        @Override
        public Set<String> collect(Set<String> out) {
            out.add(column);
            return out;
        }
    }

    /** 一元负号 */
    private record NegNode(ExprNode inner) implements ExprNode {
        @Override
        public BigDecimal eval(Map<String, BigDecimal> rowValues) {
            return inner.eval(rowValues).negate();
        }

        @Override
        public Set<String> collect(Set<String> out) {
            return inner.collect(out);
        }
    }

    /** 二元运算（+ - * /） */
    private record BinNode(char op, ExprNode left, ExprNode right) implements ExprNode {
        @Override
        public BigDecimal eval(Map<String, BigDecimal> rowValues) {
            BigDecimal l = left.eval(rowValues);
            BigDecimal r = right.eval(rowValues);
            return switch (op) {
                case '+' -> l.add(r);
                case '-' -> l.subtract(r);
                case '*' -> l.multiply(r);
                case '/' -> {
                    if (r.signum() == 0) {
                        throw new ExpressionException("除数为零（表达式含 x/0）");
                    }
                    int scale = Math.max(Math.max(l.scale(), r.scale()), DIV_SCALE_FLOOR);
                    yield l.divide(r, scale, RoundingMode.HALF_UP);
                }
                default -> throw new ExpressionException("未知运算符: " + op);
            };
        }

        @Override
        public Set<String> collect(Set<String> out) {
            left.collect(out);
            right.collect(out);
            return out;
        }
    }

    /** round(x, n)：按 n 位四舍五入收口 */
    private record RoundNode(ExprNode inner, int scale) implements ExprNode {
        @Override
        public BigDecimal eval(Map<String, BigDecimal> rowValues) {
            return inner.eval(rowValues).setScale(scale, RoundingMode.HALF_UP);
        }

        @Override
        public Set<String> collect(Set<String> out) {
            return inner.collect(out);
        }
    }

    // ---- 递归下降解析 ----

    /** 递归下降解析器：expr → term(('+'|'-') term)* → factor(('*'|'/') factor)* → primary */
    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String expr) {
            if (expr == null || expr.isBlank()) {
                throw new ExpressionException("表达式为空");
            }
            this.src = expr;
        }

        ExprNode parse() {
            ExprNode node = parseExpr();
            skipWs();
            if (pos < src.length()) {
                throw new ExpressionException("表达式存在多余字符（位置 " + pos + "）: " + src.substring(pos));
            }
            return node;
        }

        private ExprNode parseExpr() {
            ExprNode node = parseTerm();
            while (true) {
                skipWs();
                char c = peek();
                if (c == '+' || c == '-') {
                    pos++;
                    node = new BinNode(c, node, parseTerm());
                } else {
                    return node;
                }
            }
        }

        private ExprNode parseTerm() {
            ExprNode node = parseFactor();
            while (true) {
                skipWs();
                char c = peek();
                if (c == '*' || c == '/') {
                    pos++;
                    node = new BinNode(c, node, parseFactor());
                } else {
                    return node;
                }
            }
        }

        private ExprNode parseFactor() {
            skipWs();
            char c = peek();
            if (c == '-') {           // 一元负号
                pos++;
                return new NegNode(parseFactor());
            }
            if (c == '(') {
                pos++;
                ExprNode inner = parseExpr();
                expect(')');
                return inner;
            }
            if (c == '\0') {
                throw new ExpressionException("表达式意外结束");
            }
            if (Character.isDigit(c) || c == '.') {
                return new NumNode(readNumber());
            }
            if (Character.isLetter(c) || c == '_') {
                String ident = readIdent();
                skipWs();
                if ("round".equals(ident)) {
                    return parseRound();
                }
                if (peek() == '(') {
                    throw new ExpressionException("仅支持白名单函数 round()，非法调用: " + ident + "(");
                }
                return new RefNode(ident);
            }
            throw new ExpressionException("非法字符（位置 " + pos + "）: '" + c + "'");
        }

        /** round ( 表达式 , 字面量整数 ) */
        private ExprNode parseRound() {
            expect('(');
            ExprNode inner = parseExpr();
            expect(',');
            skipWs();
            int scale;
            try {
                BigDecimal n = readNumber();
                if (n.scale() != 0) {
                    throw new ExpressionException("round 第二参数必须为整数，收到: " + n);
                }
                scale = n.intValueExact();
            } catch (NumberFormatException e) {
                throw new ExpressionException("round 第二参数必须为字面量整数");
            }
            if (scale < ROUND_MIN || scale > ROUND_MAX) {
                throw new ExpressionException("round 第二参数 n 越界（允许 " + ROUND_MIN + "~" + ROUND_MAX + "），收到: " + scale);
            }
            expect(')');
            return new RoundNode(inner, scale);
        }

        /** 读取数字（整数或小数，要求至少一位数字） */
        private BigDecimal readNumber() {
            int start = pos;
            boolean digit = false;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
                digit = true;
            }
            if (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                    digit = true;
                }
            }
            if (!digit) {
                throw new ExpressionException("数字格式非法（位置 " + start + "）: " + src.substring(start));
            }
            try {
                return new BigDecimal(src.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new ExpressionException("数字格式非法: " + src.substring(start, pos), e);
            }
        }

        /** 读取标识符（列名 / 关键字） */
        private String readIdent() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_') {
                    pos++;
                } else {
                    break;
                }
            }
            return src.substring(start, pos);
        }

        private void expect(char expected) {
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != expected) {
                String got = pos < src.length() ? "'" + src.charAt(pos) + "'" : "结束";
                throw new ExpressionException("期望字符 '" + expected + "'，实际 " + got + "（位置 " + pos + "）");
            }
            pos++;
        }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }
    }
}
