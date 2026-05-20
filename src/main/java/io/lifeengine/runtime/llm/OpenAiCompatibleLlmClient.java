package io.lifeengine.runtime.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/** vLLM / OpenAI-compatible chat completions client (single provider, no fallback). */
@Component
public class OpenAiCompatibleLlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebClient webClient;
    private final RuntimeLlmProperties properties;

    public OpenAiCompatibleLlmClient(WebClient llmWebClient, RuntimeLlmProperties properties) {
        this.webClient = llmWebClient;
        this.properties = properties;
    }

    public Mono<Boolean> health() {
        return webClient
                .get()
                .uri("/health")
                .retrieve()
                .toBodilessEntity()
                .map(r -> r.getStatusCode().is2xxSuccessful())
                .timeout(properties.timeout())
                .onErrorReturn(false);
    }

    public Mono<List<String>> listModels() {
        return webClient
                .get()
                .uri("/v1/models")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(ModelsResponse.class)
                .timeout(properties.timeout())
                .map(r -> r.data().stream().map(ModelEntry::id).toList())
                .onErrorReturn(List.of());
    }

    public Mono<LlmResponse> chatCompletion(LlmRequest request) {
        String model = request.model() != null && !request.model().isBlank()
                ? request.model().trim()
                : properties.model();
        String endpoint = chatCompletionsEndpoint();
        ChatCompletionRequest body = buildRequestBody(model, request.messages());
        String requestJson = toRequestJson(body);

        log.info("LLM request endpoint={} model={} payload={}", endpoint, model, requestJson);

        return webClient
                .post()
                .uri(CHAT_COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class)
                .timeout(properties.timeout())
                .doOnSuccess(
                        response ->
                                log.info(
                                        "LLM response OK endpoint={} model={} contentLength={}",
                                        endpoint,
                                        model,
                                        toLlmResponse(response).content().length()))
                .map(this::toLlmResponse)
                .onErrorMap(
                        WebClientResponseException.class,
                        ex -> mapHttpError(ex, model, endpoint, requestJson))
                .onErrorMap(
                        ex -> !(ex instanceof LlmCallException),
                        ex -> mapTransportError(ex, model, endpoint, requestJson));
    }

    public String defaultModel() {
        return properties.model();
    }

    public String chatCompletionsEndpoint() {
        return properties.baseUrl().replaceAll("/$", "") + CHAT_COMPLETIONS_PATH;
    }

    ChatCompletionRequest buildRequestBody(String model, List<LlmMessage> messages) {
        List<ChatMessage> chatMessages =
                messages.stream().map(m -> new ChatMessage(m.role(), m.content())).toList();
        return new ChatCompletionRequest(
                model, chatMessages, properties.maxTokens(), properties.temperature());
    }

    private String toRequestJson(ChatCompletionRequest body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            return "{\"serializeError\":\"" + e.getMessage() + "\"}";
        }
    }

    private LlmCallException mapHttpError(
            WebClientResponseException ex, String model, String endpoint, String requestJson) {
        int status = ex.getStatusCode().value();
        String responseBody = ex.getResponseBodyAsString();
        if (responseBody == null) {
            responseBody = "";
        }

        log.error(
                """
                LLM request failed:
                status={}
                endpoint={}
                model={}
                request={}
                response={}
                """,
                status,
                endpoint,
                model,
                requestJson,
                responseBody);

        return LlmCallException.httpFailure(
                status, responseBody, endpoint, model, requestJson, ex);
    }

    private LlmCallException mapTransportError(
            Throwable ex, String model, String endpoint, String requestJson) {
        log.error(
                """
                LLM request failed (transport):
                endpoint={}
                model={}
                request={}
                error={}
                """,
                endpoint,
                model,
                requestJson,
                ex.toString(),
                ex);
        return LlmCallException.transport(endpoint, model, requestJson, ex);
    }

    private LlmResponse toLlmResponse(ChatCompletionResponse response) {
        String content = "";
        if (response.choices() != null && !response.choices().isEmpty()) {
            ChatMessage message = response.choices().getFirst().message();
            if (message != null && message.content() != null) {
                content = message.content().trim();
            }
        }

        Map<String, Object> usage = Map.of();
        if (response.usage() != null) {
            UsageResponse u = response.usage();
            Map<String, Object> usageMap = new LinkedHashMap<>();
            if (u.promptTokens() != null) {
                usageMap.put("prompt_tokens", u.promptTokens());
            }
            if (u.completionTokens() != null) {
                usageMap.put("completion_tokens", u.completionTokens());
            }
            if (u.totalTokens() != null) {
                usageMap.put("total_tokens", u.totalTokens());
            }
            usage = Map.copyOf(usageMap);
        }

        return new LlmResponse(content, usage);
    }

    /** OpenAI-compatible POST body (snake_case field names for vLLM). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatCompletionRequest(
            String model,
            List<ChatMessage> messages,
            @JsonProperty("max_tokens") int maxTokens,
            double temperature) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatMessage(String role, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatCompletionResponse(List<Choice> choices, UsageResponse usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(ChatMessage message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UsageResponse(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelsResponse(List<ModelEntry> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelEntry(String id) {}
}
