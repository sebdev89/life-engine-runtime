package io.lifeengine.runtime.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Per-role LLM endpoint configuration.
 *
 * <p>Binds to the same {@code runtime.llm} prefix as {@link RuntimeLlmProperties} but reads the
 * {@code fast}, {@code chat}, {@code smart}, and {@code coding} sub-objects. The top-level
 * {@code runtime.llm.*} fields are left to {@link RuntimeLlmProperties} for backward compat.
 *
 * <p>Each role defaults to a safe local value so the app starts without any environment variable.
 * Override via {@code runtime.llm.fast.*} / {@code runtime.llm.chat.*} / etc. in YAML or env.
 */
@ConfigurationProperties(prefix = "runtime.llm")
public record RuntimeMultiModelProperties(
        RuntimeLlmProperties fast,
        RuntimeLlmProperties chat,
        RuntimeLlmProperties smart,
        RuntimeLlmProperties coding) {

    @ConstructorBinding
    public RuntimeMultiModelProperties {
        if (fast == null) {
            fast = new RuntimeLlmProperties(
                    "http://localhost:8000",
                    "Qwen/Qwen2.5-Coder-3B-Instruct",
                    "local-dev",
                    Duration.ofSeconds(20),
                    256,
                    0.0);
        }
        if (chat == null) {
            chat = new RuntimeLlmProperties(
                    "http://localhost:11434",
                    "gemma3:4b",
                    "local-dev",
                    Duration.ofSeconds(60),
                    512,
                    0.3);
        }
        if (smart == null) {
            smart = new RuntimeLlmProperties(
                    "http://localhost:8002",
                    "deepseek-ai/DeepSeek-R1-Distill-Qwen-14B",
                    "local-dev",
                    Duration.ofSeconds(180),
                    2048,
                    0.0,
                    LlmRetryConfig.DISABLED);
        }
        if (coding == null) {
            coding = new RuntimeLlmProperties(
                    "http://localhost:8000",
                    "Qwen/Qwen2.5-Coder-3B-Instruct",
                    "local-dev",
                    Duration.ofSeconds(90),
                    1024,
                    0.0);
        }
    }
}
