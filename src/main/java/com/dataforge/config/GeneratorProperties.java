package com.dataforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DataForge 生成参数（application.yml dataforge.* 前缀，CLI 参数可覆盖）。
 *
 * @param mockLlm        --mock-llm 干跑模式（确定性 Mock LLM）
 * @param seed           确定性种子（规则层随机源，可复现）
 * @param batchSize      初始批次大小（batch 自适应会动态降批）
 * @param maxConcurrency 表内批次并行度（虚拟线程，防限流）
 * @param pkStrategy     PK 策略：sequential | snowflake
 * @param cacheDir       生成缓存目录（runFingerprint + 表名 → 产物 JSON）
 * @param minParentRows  引用闭包中未指定行数的父表最小行数
 * @param maxIterations  修正 agent 收敛循环上限（P3）
 */
@ConfigurationProperties(prefix = "dataforge")
public record GeneratorProperties(
        boolean mockLlm,
        long seed,
        int batchSize,
        int maxConcurrency,
        String pkStrategy,
        String cacheDir,
        int minParentRows,
        int maxIterations
) {
}
