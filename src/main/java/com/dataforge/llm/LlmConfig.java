package com.dataforge.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * LLM 装配链（P8 主备双链路 + 可靠性装饰器，真实模式）。
 *
 * <p>mock 模式（dataforge.mock-llm=true）由 {@link MockLlmClient}（@Service 条件装配）提供 DataLlm，
 * 不接装饰器（确定性快速，超时/熔断无意义）。</p>
 *
 * <p>真实模式（默认）：主模型走 spring.ai.openai.* 自动配置（LLM_BASE_URL/LLM_API_KEY/LLM_MODEL），
 * 备模型走 LLM_BACKUP_*（建议 qwen 等第二模型，未配则退化为主模型配置），
 * 最终 DataLlm = Failover(主: CircuitBreaker(Timeout(primary)), 备: backup)——熔断 + 超时 + 主备降级全部生效。</p>
 */
@Configuration
public class LlmConfig {

    /** 主模型 ChatClient（spring.ai.openai.* 自动配置；.env 的 LLM_API_KEY/LLM_BASE_URL/LLM_MODEL） */
    @Bean
    @ConditionalOnProperty(name = "dataforge.mock-llm", havingValue = "false", matchIfMissing = true)
    public ChatClient primaryChatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /** 备模型 ChatClient（LLM_BACKUP_*；未配默认 dashscope qwen-flash，可退化为主模型配置） */
    @Bean
    @ConditionalOnProperty(name = "dataforge.mock-llm", havingValue = "false", matchIfMissing = true)
    public ChatClient backupChatClient() {
        String baseUrl = System.getProperty("LLM_BACKUP_BASE_URL",
                "https://dashscope.aliyuncs.com/compatible-mode/v1");
        String apiKey = System.getProperty("LLM_BACKUP_API_KEY",
                System.getProperty("LLM_API_KEY", "sk-xxx"));
        String model = System.getProperty("LLM_BACKUP_MODEL", "qwen-flash");
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        OpenAiChatModel backupModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).temperature(0.3).build())
                .build();
        return ChatClient.builder(backupModel).build();
    }

    @Bean
    @ConditionalOnProperty(name = "dataforge.mock-llm", havingValue = "false", matchIfMissing = true)
    public DataLlm primaryLlm(ChatClient primaryChatClient) {
        return new RealLlmClient(primaryChatClient);
    }

    @Bean
    @ConditionalOnProperty(name = "dataforge.mock-llm", havingValue = "false", matchIfMissing = true)
    public DataLlm backupLlm(ChatClient backupChatClient) {
        return new RealLlmClient(backupChatClient);
    }

    /**
     * 最终 DataLlm：主链路外包 Timeout → CircuitBreaker → Failover(备)；熔断快速失败由上层降批/失败表隔离兜底。
     *
     * <p>容器内有 3 个 DataLlm 类型 bean（primaryLlm / backupLlm / dataLlm），必须显式标注
     * {@code @Primary} 供外部（DefaultAnalysisAgent 等）注入最终链路；同时本方法的两个参数必须
     * 用 {@code @Qualifier} 精确指定——否则参数解析会被自身的 {@code @Primary} 吸附，形成自注入循环依赖。</p>
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "dataforge.mock-llm", havingValue = "false", matchIfMissing = true)
    public DataLlm dataLlm(@Qualifier("primaryLlm") DataLlm primaryLlm,
                           @Qualifier("backupLlm") DataLlm backupLlm) {
        return new FailoverLlmClient(
                new CircuitBreakerLlmClient(new TimeoutLlmClient(primaryLlm)),
                backupLlm);
    }
}
