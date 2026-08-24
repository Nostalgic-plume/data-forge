package com.dataforge;

import com.dataforge.config.EnvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.Arrays;

/**
 * DataForge 启动类（CLI 模式）。
 *
 * <p>启动顺序：加载 .env → 解析 --mock-llm 参数（干跑模式）→ 启动容器。</p>
 *
 * <p>--mock-llm 重写为命令行属性 <code>--dataforge.mock-llm=true</code>：
 * 命令行属性优先级最高（高于 application.yml），保证 MockLlmClient 生效；
 * 同时排除 OpenAI 自动装配（不创建真实客户端）。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DataForgeApplication {

    public static void main(String[] args) {
        // 1. 加载 .env 到系统属性（application.yml ${LLM_*} 占位解析依赖）
        EnvLoader.load(".env");

        // 2. --mock-llm：干跑模式 —— 命令行属性覆盖 yml 默认，注入 MockLlmClient
        if (hasArg(args, "--mock-llm")) {
            args = Arrays.copyOf(args, args.length + 2);
            args[args.length - 2] = "--dataforge.mock-llm=true";
            args[args.length - 1] = "--spring.autoconfigure.exclude="
                    + "org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration";
            System.out.println("[DataForge] --mock-llm 干跑模式：使用确定性 Mock LLM，无需 API Key");
        }

        // 3. --web：P7 Web 模式 —— 启动 Web 服务器（REST + 静态前端），覆盖 yml 的 web-application-type: none
        if (hasArg(args, "--web")) {
            args = Arrays.copyOf(args, args.length + 2);
            args[args.length - 2] = "--spring.main.web-application-type=servlet";
            args[args.length - 1] = "--server.port=" + webPort(args);
            System.out.println("[DataForge] --web 模式：启动 Web 界面（http://localhost:" + webPort(args) + "）");
        }

        // 4. 启动容器（CommandLineRunner = DataForgeCli；--web 时 CLI 跳过，跑 Web 服务）
        SpringApplication.run(DataForgeApplication.class, args);
    }

    private static boolean hasArg(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) return true;
        }
        return false;
    }

    /** --port 可选端口（默认 8080） */
    private static int webPort(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equals(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException ignored) {
                    return 8080;
                }
            }
        }
        return 8080;
    }
}
