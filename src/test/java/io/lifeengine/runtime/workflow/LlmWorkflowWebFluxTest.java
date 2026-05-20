package io.lifeengine.runtime.workflow;

import io.lifeengine.runtime.api.RunResponse;
import io.lifeengine.runtime.api.RuntimeEventResponse;
import io.lifeengine.runtime.app.RuntimeApplication;
import io.lifeengine.runtime.domain.RunStatus;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class LlmWorkflowWebFluxTest {

    private static MockWebServer mockLlm;

    @Autowired
    private WebTestClient webTestClient;

    @BeforeAll
    static void startMockLlm() throws IOException {
        mockLlm = new MockWebServer();
        mockLlm.start();
    }

    @AfterAll
    static void stopMockLlm() throws IOException {
        if (mockLlm != null) {
            mockLlm.shutdown();
        }
    }

    @DynamicPropertySource
    static void llmBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("runtime.llm.base-url", () -> mockLlm.url("/").toString().replaceAll("/$", ""));
        registry.add("runtime.llm.model", () -> "test-model");
        registry.add("runtime.llm.api-key", () -> "test-key");
    }

    @Test
    void demoLlmWorkflow_doesNotEmitFakeAgentIds() {
        enqueueChatCompletion("Summary.");
        enqueueChatCompletion("INFO");

        UUID runId = startLlmRun("no fake agents");
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<String> types = collectEventTypes(runId);
        org.assertj.core.api.Assertions.assertThat(types).contains("WORKFLOW_STARTED", "LLM_REQUESTED");
        org.assertj.core.api.Assertions.assertThat(types).doesNotContain("RUN_STARTED");
        org.assertj.core.api.Assertions.assertThat(types.stream().noneMatch(t -> t.equals("AGENT_STARTED"))).isFalse();

        webTestClient
                .get()
                .uri("/api/runtime/runs/{runId}", runId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.metadata.executor")
                .isEqualTo("llm");
    }

    @Test
    void demoLlmWorkflow_succeedsWithLlmEventsInOrder() {
        int requestsBefore = mockLlm.getRequestCount();
        enqueueChatCompletion("Concise summary of the input.");
        enqueueChatCompletion("INFO");

        UUID runId = startLlmRun("Summarize this operational alert.");
        List<String> types = collectEventTypes(runId);
        awaitTerminal(runId, RunStatus.SUCCEEDED);
        org.assertj.core.api.Assertions.assertThat(types)
                .containsSubsequence(
                        List.of(
                                "WORKFLOW_STARTED",
                                "AGENT_STARTED",
                                "LLM_REQUESTED",
                                "LLM_COMPLETED",
                                "AGENT_COMPLETED",
                                "AGENT_STARTED",
                                "LLM_REQUESTED",
                                "LLM_COMPLETED",
                                "AGENT_COMPLETED",
                                "WORKFLOW_COMPLETED",
                                "RUN_COMPLETED"));
        org.assertj.core.api.Assertions.assertThat(types.stream().filter(t -> t.equals("LLM_REQUESTED")).count())
                .isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(types.stream().filter(t -> t.equals("LLM_COMPLETED")).count())
                .isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(mockLlm.getRequestCount() - requestsBefore).isEqualTo(2);
    }

    @Test
    void demoLlmWorkflow_whenLlmFails_runFailsWithLlmFailed() {
        mockLlm.enqueue(
                new MockResponse()
                        .setResponseCode(400)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"error\":{\"message\":\"model unavailable\"}}"));

        UUID runId = startLlmRun("trigger failure");
        awaitTerminal(runId, RunStatus.FAILED);

        List<String> types = collectEventTypes(runId);
        org.assertj.core.api.Assertions.assertThat(types).contains("LLM_FAILED", "RUN_FAILED");
        org.assertj.core.api.Assertions.assertThat(types).doesNotContain("RUN_COMPLETED");

        List<io.lifeengine.runtime.api.RuntimeEventResponse> events = collectEvents(runId);
        io.lifeengine.runtime.api.RuntimeEventResponse failed =
                events.stream().filter(e -> "LLM_FAILED".equals(e.type())).findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThat(failed.attributes().get("statusCode")).isEqualTo("400");
        org.assertj.core.api.Assertions.assertThat(failed.attributes().get("endpoint"))
                .contains("/v1/chat/completions");
        org.assertj.core.api.Assertions.assertThat(failed.attributes().get("responseBodyPreview"))
                .contains("model unavailable");
    }

    private List<io.lifeengine.runtime.api.RuntimeEventResponse> collectEvents(UUID runId) {
        return webTestClient
                .get()
                .uri("/api/runtime/runs/{runId}/events", runId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(io.lifeengine.runtime.api.RuntimeEventResponse.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(10));
    }

    @Test
    void demoLlmWorkflow_lateSseSubscriber_replaysLlmEvents() throws InterruptedException {
        enqueueChatCompletion("Summary text.");
        enqueueChatCompletion("RISK");

        UUID runId = startLlmRun("late subscriber check");
        awaitTerminal(runId, RunStatus.SUCCEEDED);
        Thread.sleep(50);

        List<RuntimeEventResponse> replayed =
                webTestClient
                        .get()
                        .uri("/api/runtime/runs/{runId}/events", runId)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .returnResult(RuntimeEventResponse.class)
                        .getResponseBody()
                        .collectList()
                        .block(Duration.ofSeconds(5));

        org.assertj.core.api.Assertions.assertThat(replayed).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(
                        replayed.stream().map(RuntimeEventResponse::type).filter(t -> t.equals("LLM_COMPLETED")).count())
                .isGreaterThanOrEqualTo(2);
    }

    private void enqueueChatCompletion(String content) {
        mockLlm.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                """
                                {
                                  "choices": [{"message": {"role": "assistant", "content": "%s"}}],
                                  "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                                }
                                """
                                        .formatted(content.replace("\"", "\\\""))));
    }

    private UUID startLlmRun(String input) {
        String json =
                """
                {"workflowId":"demo.llm.workflow","input":%s}
                """
                        .formatted(toJsonString(input));
        return webTestClient
                .post()
                .uri("/api/runtime/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(RunResponse.class)
                .returnResult()
                .getResponseBody()
                .runId();
    }

    private static String toJsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private List<String> collectEventTypes(UUID runId) {
        List<RuntimeEventResponse> events =
                webTestClient
                        .get()
                        .uri("/api/runtime/runs/{runId}/events", runId)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .returnResult(RuntimeEventResponse.class)
                        .getResponseBody()
                        .collectList()
                        .block(Duration.ofSeconds(10));
        return events == null
                ? List.of()
                : events.stream().map(RuntimeEventResponse::type).toList();
    }

    private void awaitTerminal(UUID runId, RunStatus expected) {
        Duration timeout = Duration.ofSeconds(10);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            RunStatus status =
                    webTestClient
                            .get()
                            .uri("/api/runtime/runs/{runId}", runId)
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody(RunResponse.class)
                            .returnResult()
                            .getResponseBody()
                            .status();
            if (status == expected) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        org.assertj.core.api.Assertions.fail("Run did not reach " + expected + " in time");
    }
}
