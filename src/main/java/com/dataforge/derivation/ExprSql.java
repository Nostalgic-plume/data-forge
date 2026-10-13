package com.dataforge.derivation;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表达式 → SQL 片段翻译（EXPR_EQUAL 的校验侧与修正侧共用这一份实现）。
 *
 * <p>与生成侧同源：语法校验复用 {@link ExpressionEvaluator}，保证"生成时算得对"与
 * "校验/修正时查得对"用的是同一套语法。翻译顺序不可交换——<b>先字符集白名单，再语法解析</b>：
 * 反过来的话 {@code a; DROP TABLE b} 会先撞解析器，抛出的错误类型与信息都不是护栏该给的。</p>
 *
 * <p>护栏：非法字符、未声明标识符一律抛出，不做"拼完再放行"。</p>
 */
public final class ExprSql {

    private ExprSql() {}

    /**
     * 允许的字符集（列名/数字/四则运算/括号/逗号——逗号为 round(x,n) 预留）。
     * 用 {@code \p{L}\p{N}} 而非 ASCII：{@link ExpressionEvaluator} 以
     * {@code Character.isLetter/isDigit} 判标识符（Unicode 全量），只放行 ASCII 会造成
     * "生成侧能用、校验侧被拒"的能力割裂。
     */
    private static final Pattern ALLOWED = Pattern.compile("^[\\p{L}\\p{N}_+\\-*/().,\\s]+$");

    /**
     * 表达式中的标识符（Unicode 字母/下划线开头；lookbehind 防从数字中间截断）。
     * <b>必须与 {@link #ALLOWED} 同口径放宽</b>——否则 Unicode 列名通过了白名单却匹配不到，
     * 会不被反引号包裹就拼进 SQL。
     */
    private static final Pattern IDENT =
            Pattern.compile("(?<![\\p{L}\\p{N}_])[\\p{L}_][\\p{L}\\p{N}_]*");

    /** 允许原样保留的函数名（MySQL 不允许反引号包裹函数名） */
    private static final Set<String> FUNCS = Set.of("round");

    /**
     * 表达式 → SQL 片段（标识符反引号化）。
     *
     * @param where 抛错信息里的定位（如 "规则 orders.actual_amount"），便于定位是哪条规则写的
     * @throws IllegalStateException 空表达式 / 非法字符 / 语法错误 / 未声明标识符
     */
    public static String toSql(String expr, String where) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalStateException("表达式为空（" + where + "）");
        }
        if (!ALLOWED.matcher(expr).matches()) {
            throw new IllegalStateException("表达式含非法字符（仅允许列名/数字/+-*/()，" + where + "）: " + expr);
        }
        Set<String> refs;
        try {
            refs = ExpressionEvaluator.referencedColumns(expr);   // 语法校验
        } catch (ExpressionException e) {
            throw new IllegalStateException("表达式语法错误（" + where + "）: " + e.getMessage(), e);
        }
        Matcher m = IDENT.matcher(expr);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String id = m.group();
            String replacement;
            if (FUNCS.contains(id.toLowerCase())) {
                replacement = id;
            } else if (refs.contains(id)) {
                replacement = "`" + id + "`";
            } else {
                throw new IllegalStateException("表达式含未声明标识符 `" + id + "`（" + where + "）");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
