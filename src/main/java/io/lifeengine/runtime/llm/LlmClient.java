package io.lifeengine.runtime.llm;

import java.util.List;
import reactor.core.publisher.Mono;

/** OpenAI-compatible LLM adapter contract. */
public interface LlmClient {

    Mono<LlmResponse> chatCompletion(LlmRequest request);

    String defaultModel();

    String chatCompletionsEndpoint();

    Mono<Boolean> health();

    Mono<List<String>> listModels();

    /**
     * Retry policy applied around {@link #chatCompletion(LlmRequest)} by orchestration code (e.g.
     * {@code LlmAgentSupport}). Defaults to {@link LlmRetryConfig#DISABLED} so adapters that don't
     * model retry stay strict.
     */
    default LlmRetryConfig retryConfig() {
        return LlmRetryConfig.DISABLED;
    }
}
