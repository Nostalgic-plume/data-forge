package com.dataforge.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * .env 文件加载器 — 在 Spring 容器启动前执行，把 KEY=VALUE 写入系统属性，
 * 供 application.yml 的 ${LLM_*} 占位符解析。
 *
 * <p>凭证一律走 .env（已 gitignore），不写进代码/仓库。读取优先级：
 * 系统环境变量 > .env 文件。</p>
 */
public final class EnvLoader {

    private static final Logger log = LoggerFactory.getLogger(EnvLoader.class);

    private EnvLoader() {}

    /** 从项目根目录 .env 加载并写入系统属性（仅当同名系统属性不存在时）。 */
    public static void load(String dotEnvPath) {
        Path path = Path.of(dotEnvPath);
        if (!Files.exists(path)) {
            log.warn(".env 文件不存在：{}（LLM 凭证需配置，或用 --mock-llm 干跑）", path.toAbsolutePath());
            return;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isBlank() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (key.isBlank()) continue;
                if (System.getProperty(key) == null) {
                    System.setProperty(key, value);
                }
            }
            log.info(".env 加载完成：{}", path.toAbsolutePath());
        } catch (IOException e) {
            log.warn(".env 读取失败（忽略，继续启动）：{}", e.getMessage());
        }
    }
}
