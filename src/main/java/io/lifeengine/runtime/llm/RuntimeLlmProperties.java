package io.lifeengine.runtime.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "runtime.llm")
public record RuntimeLlmProperties(
        String baseUrl,
        String model,
        String apiKey,
        Duration timeout,
        int maxTokens,
        double temperature,
        LlmRetryConfig retry) {

    @ConstructorBinding
    public RuntimeLlmProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8000";
        }
        if (model == null || model.isBlank()) {
            model = "Qwen/Qwen2.5-Coder-3B-Instruct";
        }
        if (apiKey == null) {
            apiKey = "local-dev";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(30);
        }
        if (maxTokens <= 0) {
            maxTokens = 256;
        }
        if (retry == null) {
            // Conservative default: retry transient transport/provider failures up to twice with
            // a small fixed backoff. Disable by setting `runtime.llm.retry.enabled: false`.
            retry = new LlmRetryConfig(true, 2, 200L);
        }
    }

    /**
     * Convenience constructor for tests that predate the retry field.
     *
     * <p>Uses the conservative default retry policy so existing call sites keep compiling without
     * having to thread retry config through.
     */
    public RuntimeLlmProperties(
            String baseUrl,
            String model,
            String apiKey,
            Duration timeout,
            int maxTokens,
            double temperature) {
        this(baseUrl, model, apiKey, timeout, maxTokens, temperature, null);
    }
}
