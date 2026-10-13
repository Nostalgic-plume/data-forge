package com.dataforge.config;

/**
 * 全局默认值集中定义（消除散落各处的字面量）。
 *
 * <p>背景：默认 schema 原本以 "shop" 字面量散落在 4 个文件共 12 处，
 * 换库或调整默认值时容易漏改，也不利于判断"哪些是默认值、哪些是业务表引用"。</p>
 *
 * <p>取值方式与 {@code DataForgeApiController#config} 既有实现保持一致：
 * 优先读系统属性 {@code DB_SCHEMA}（由 {@link EnvLoader} 从 .env 注入），
 * 未配置时回落到内置默认值。</p>
 */
public final class Defaults {

    private Defaults() {}

    /** 内置兜底 schema（未配置 DB_SCHEMA 时使用） */
    private static final String FALLBACK_SCHEMA = "shop";

    /** 未指定库名时的兜底 schema，可由 .env 的 DB_SCHEMA 覆盖 */
    public static String schema() {
        return System.getProperty("DB_SCHEMA", FALLBACK_SCHEMA);
    }
}
