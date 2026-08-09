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

    /**
     * Rol de Multi-Model V2 que sirvió la llamada — {@code chat}, {@code fast} o {@code default}.
     *
     * <p>Sin esto, el cockpit puede mostrar QUÉ modelo respondió pero no POR QUÉ ése: ver
     * "gemma3:4b" no dice si el agente pidió el rol rápido o si su {@code @Qualifier} se cayó y
     * terminó en el cliente {@code @Primary}. Los dos casos se ven idénticos en pantalla y sólo
     * uno está bien.
     */
    default String role() {
        return "default";
    }
}
