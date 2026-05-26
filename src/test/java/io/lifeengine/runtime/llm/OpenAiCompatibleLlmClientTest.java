package io.lifeengine.runtime.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class OpenAiCompatibleLlmClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static MockWebServer mockLlm;

    @BeforeAll
    static void startServer() throws IOException {
        mockLlm = new MockWebServer();
        mockLlm.start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        if (mockLlm != null) {
            mockLlm.shutdown();
        }
    }

    @BeforeEach
    void drainPendingRequests() throws InterruptedException {
        RecordedRequest req;
        while ((req = mockLlm.takeRequest(100, TimeUnit.MILLISECONDS)) != null) {
            // discard requests left by a previous test
        }
    }

    @Test
    void chatCompletion_sendsOpenAiCompatiblePayload() throws Exception {
        mockLlm.enqueue(successResponse("Hello from vLLM."));

        OpenAiCompatibleLlmClient client = client(64, 0.0);

        StepVerifier.create(
                        client.chatCompletion(
                                new LlmRequest(
                                        null,
                                        List.of(
                                                new LlmMessage("system", "You are helpful."),
                                                new LlmMessage("user", "Say hello in one sentence.")))))
                .assertNext(
                        response -> {
                            org.assertj.core.api.Assertions.assertThat(response.content())
                                    .isEqualTo("Hello from vLLM.");
                            org.assertj.core.api.Assertions.assertThat(response.usage().get("prompt_tokens"))
                                    .isEqualTo(12);
                            org.assertj.core.api.Assertions.assertThat(response.usage().get("completion_tokens"))
                                    .isEqualTo(8);
                            org.assertj.core.api.Assertions.assertThat(response.usage().get("total_tokens"))
                                    .isEqualTo(20);
                        })
                .verifyComplete();

        RecordedRequest recorded = mockLlm.takeRequest(5, TimeUnit.SECONDS);
        org.assertj.core.api.Assertions.assertThat(recorded.getMethod()).isEqualTo("POST");
        org.assertj.core.api.Assertions.assertThat(recorded.getPath()).isEqualTo("/v1/chat/completions");

        JsonNode body = JSON.readTree(recorded.getBody().readUtf8());
        org.assertj.core.api.Assertions.assertThat(body.get("model").asText())
                .isEqualTo("Qwen/Qwen2.5-Coder-3B-Instruct");
        org.assertj.core.api.Assertions.assertThat(body.get("max_tokens").asInt()).isEqualTo(64);
        org.assertj.core.api.Assertions.assertThat(body.get("temperature").asDouble()).isEqualTo(0.0);
        org.assertj.core.api.Assertions.assertThat(body.get("messages").isArray()).isTrue();
        org.assertj.core.api.Assertions.assertThat(body.get("messages")).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(body.get("messages").get(0).get("role").asText())
                .isEqualTo("system");
        org.assertj.core.api.Assertions.assertThat(body.get("messages").get(0).get("content").asText())
                .isEqualTo("You are helpful.");
        org.assertj.core.api.Assertions.assertThat(body.get("messages").get(1).get("role").asText())
                .isEqualTo("user");
        org.assertj.core.api.Assertions.assertThat(body.get("messages").get(1).get("content").asText())
                .isNotBlank();
        org.assertj.core.api.Assertions.assertThat(body.has("maxTokens")).isFalse();
    }

    @Test
    void chatCompletion_onHttp503_marksFailureAsTransientForRetry() {
        mockLlm.enqueue(
                new MockResponse()
                        .setResponseCode(503)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"error\":{\"message\":\"backend overloaded\"}}"));

        OpenAiCompatibleLlmClient client = client(256, 0.0);

        StepVerifier.create(
                        client.chatCompletion(
                                new LlmRequest(
                                        "Qwen/Qwen2.5-Coder-3B-Instruct",
                                        List.of(new LlmMessage("user", "hello")))))
                .expectErrorSatisfies(
                        err -> {
                            org.assertj.core.api.Assertions.assertThat(err).isInstanceOf(LlmCallException.class);
                            LlmCallException llm = (LlmCallException) err;
                            org.assertj.core.api.Assertions.assertThat(llm.statusCode()).isEqualTo(503);
                            org.assertj.core.api.Assertions.assertThat(llm.isTransient()).isTrue();
                        })
                .verify();
    }

    @Test
    void chatCompletion_onHttp400_exposesStatusAndBody() {
        String errorJson = "{\"error\":{\"message\":\"model not found\",\"type\":\"invalid_request\"}}";
        mockLlm.enqueue(
                new MockResponse()
                        .setResponseCode(400)
                        .setHeader("Content-Type", "application/json")
                        .setBody(errorJson));

        OpenAiCompatibleLlmClient client = client(256, 0.0);

        StepVerifier.create(
                        client.chatCompletion(
                                new LlmRequest(
                                        "Qwen/Qwen2.5-Coder-3B-Instruct",
                                        List.of(new LlmMessage("user", "hello")))))
                .expectErrorSatisfies(
                        err -> {
                            org.assertj.core.api.Assertions.assertThat(err)
                                    .isInstanceOf(LlmCallException.class);
                            LlmCallException llm = (LlmCallException) err;
                            org.assertj.core.api.Assertions.assertThat(llm.statusCode()).isEqualTo(400);
                            org.assertj.core.api.Assertions.assertThat(llm.responseBody())
                                    .contains("model not found");
                            org.assertj.core.api.Assertions.assertThat(llm.endpoint())
                                    .endsWith("/v1/chat/completions");
                            org.assertj.core.api.Assertions.assertThat(llm.model())
                                    .isEqualTo("Qwen/Qwen2.5-Coder-3B-Instruct");
                            org.assertj.core.api.Assertions.assertThat(llm.getCause()).isNotNull();
                            org.assertj.core.api.Assertions.assertThat(llm.isTransient()).isFalse();
                        })
                .verify();
    }

    private static MockResponse successResponse(String content) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                        """
                        {
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "%s"
                              }
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 12,
                            "completion_tokens": 8,
                            "total_tokens": 20
                          }
                        }
                        """
                                .formatted(content.replace("\"", "\\\"")));
    }

    private OpenAiCompatibleLlmClient client(int maxTokens, double temperature) {
        String baseUrl = mockLlm.url("/").toString().replaceAll("/$", "");
        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();
        RuntimeLlmProperties props =
                new RuntimeLlmProperties(
                        baseUrl,
                        "Qwen/Qwen2.5-Coder-3B-Instruct",
                        "test",
                        Duration.ofSeconds(5),
                        maxTokens,
                        temperature);
        return new OpenAiCompatibleLlmClient(
                webClient, props, new io.lifeengine.runtime.observability.RuntimeMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }
}
