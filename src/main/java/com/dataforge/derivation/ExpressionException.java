package com.dataforge.derivation;

/**
 * 派生列表达式错误（解析/求值阶段）。
 *
 * <p>显式失败语义（derive-universal-design.md §H4）：任何表达式问题都抛此异常向上传导，
 * 由生成侧包成表失败隔离——绝不静默填 0 / null 占位。</p>
 */
public class ExpressionException extends RuntimeException {

    public ExpressionException(String message) {
        super(message);
    }

    public ExpressionException(String message, Throwable cause) {
        super(message, cause);
    }
}
