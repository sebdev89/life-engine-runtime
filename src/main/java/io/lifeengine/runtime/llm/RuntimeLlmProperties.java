package io.lifeengine.runtime.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "runtime.llm")
public record RuntimeLlmProperties(
        String baseUrl,
        String model,
        String apiKey,
        Duration timeout,
        int maxTokens,
        double temperature) {

    public RuntimeLlmProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8000";
        }
        if (model == null || model.isBlank()) {
            model = "Qwen/Qwen2.5-Coder-7B-Instruct";
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
    }
}
