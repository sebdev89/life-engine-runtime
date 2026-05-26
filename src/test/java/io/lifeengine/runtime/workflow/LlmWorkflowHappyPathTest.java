package io.lifeengine.runtime.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.api.RunResponse;
import io.lifeengine.runtime.api.RuntimeEventResponse;
import io.lifeengine.runtime.app.RuntimeApplication;
import io.lifeengine.runtime.domain.RunStatus;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.assertj.core.api.Assertions;
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

/**
 * End-to-end happy path for {@code demo.llm.workflow} with MockWebServer standing in for vLLM.
 */
@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class LlmWorkflowHappyPathTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String INPUT =
            "[INCIDENT] CPU saturation exceeded 92% for 8 minutes on node-3 (prod-compute)."
                    + " [ACTION REQUIRED] Review horizontal scaling policy and deployments from the last 2 hours.";

    private static final String SUMMARIZER_JSON =
            """
            {"incident":"CPU saturation on node-3","affectedResource":"node-3","requestedAction":"Review scaling policy and recent deploys"}
            """
                    .trim();

    private static final String CLASSIFIER_JSON =
            """
            {"category":"ACTION","reason":"The input asks to review scaling policy and recent deploys."}
            """
                    .trim();

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
    void demoLlmWorkflow_happyPath_endToEnd() throws Exception {
        int requestsBefore = mockLlm.getRequestCount();
        enqueueChatCompletion(SUMMARIZER_JSON);
        enqueueChatCompletion(CLASSIFIER_JSON);

        UUID runId = startLlmRun(INPUT);
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        RunResponse run = getRun(runId);
        Assertions.assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        Assertions.assertThat(run.workflowId()).isEqualTo(WorkflowIds.DEMO_LLM);
        Assertions.assertThat(run.metadata().get("executor")).isEqualTo("llm");

        List<RuntimeEventResponse> events = fetchEventsAfterTerminal(runId);
        assertHappyPathEventSequence(events);
        assertNoFailureEvents(events);
        assertLlmCompletedPreviews(events);
        assertAgentStructuredOutputs(events);

        RuntimeEventResponse runCompleted =
                events.stream().filter(e -> "RUN_SUCCEEDED".equals(e.type())).findFirst().orElseThrow();
        Assertions.assertThat(runCompleted.terminal()).isTrue();

        Assertions.assertThat(mockLlm.getRequestCount() - requestsBefore).isEqualTo(2);
        assertChatCompletionRequests();

        List<RuntimeEventResponse> replayed = fetchEventsAfterTerminal(runId);
        Assertions.assertThat(replayed).hasSize(events.size());
        Assertions.assertThat(replayed.stream().map(RuntimeEventResponse::type).toList())
                .containsExactlyElementsOf(events.stream().map(RuntimeEventResponse::type).toList());
    }

    private void assertHappyPathEventSequence(List<RuntimeEventResponse> events) {
        List<ExpectedEvent> expected =
                List.of(
                        new ExpectedEvent("RUN_STARTED", null),
                        new ExpectedEvent("STAGE_STARTED", null),
                        new ExpectedEvent("AGENT_STARTED", "summarizer-agent"),
                        new ExpectedEvent("LLM_CALL_STARTED", "summarizer-agent"),
                        new ExpectedEvent("LLM_CALL_SUCCEEDED", "summarizer-agent"),
                        new ExpectedEvent("AGENT_SUCCEEDED", "summarizer-agent"),
                        new ExpectedEvent("STAGE_SUCCEEDED", null),
                        new ExpectedEvent("STAGE_STARTED", null),
                        new ExpectedEvent("AGENT_STARTED", "classifier-agent"),
                        new ExpectedEvent("LLM_CALL_STARTED", "classifier-agent"),
                        new ExpectedEvent("LLM_CALL_SUCCEEDED", "classifier-agent"),
                        new ExpectedEvent("AGENT_SUCCEEDED", "classifier-agent"),
                        new ExpectedEvent("STAGE_SUCCEEDED", null),
                        new ExpectedEvent("RUN_SUCCEEDED", null));

        List<ExpectedEvent> actual =
                events.stream().map(e -> new ExpectedEvent(e.type(), e.agentId())).toList();

        Assertions.assertThat(actual).containsExactlyElementsOf(expected);
    }

    private void assertNoFailureEvents(List<RuntimeEventResponse> events) {
        List<String> types = events.stream().map(RuntimeEventResponse::type).toList();
        Assertions.assertThat(types)
                .doesNotContain("RUN_FAILED", "LLM_CALL_FAILED", "AGENT_FAILED");
    }

    private void assertLlmCompletedPreviews(List<RuntimeEventResponse> events) {
        RuntimeEventResponse summarizerLlm =
                events.stream()
                        .filter(e -> "LLM_CALL_SUCCEEDED".equals(e.type()))
                        .filter(e -> "summarizer-agent".equals(e.agentId()))
                        .findFirst()
                        .orElseThrow();
        Assertions.assertThat(summarizerLlm.payload().get("responsePreview"))
                .contains("\"incident\"");

        RuntimeEventResponse classifierLlm =
                events.stream()
                        .filter(e -> "LLM_CALL_SUCCEEDED".equals(e.type()))
                        .filter(e -> "classifier-agent".equals(e.agentId()))
                        .findFirst()
                        .orElseThrow();
        Assertions.assertThat(classifierLlm.payload().get("responsePreview")).contains("\"category\":\"ACTION\"");
    }

    private void assertAgentStructuredOutputs(List<RuntimeEventResponse> events) {
        RuntimeEventResponse summarizerDone =
                events.stream()
                        .filter(e -> "AGENT_SUCCEEDED".equals(e.type()))
                        .filter(e -> "summarizer-agent".equals(e.agentId()))
                        .findFirst()
                        .orElseThrow();
        Assertions.assertThat(summarizerDone.payload().get("incident")).contains("CPU saturation");
        Assertions.assertThat(summarizerDone.payload().get("affectedResource")).isEqualTo("node-3");

        RuntimeEventResponse classifierDone =
                events.stream()
                        .filter(e -> "AGENT_SUCCEEDED".equals(e.type()))
                        .filter(e -> "classifier-agent".equals(e.agentId()))
                        .findFirst()
                        .orElseThrow();
        Assertions.assertThat(classifierDone.payload().get("category")).isEqualTo("ACTION");
        Assertions.assertThat(classifierDone.payload().get("reason")).contains("scaling policy");
    }

    private void assertChatCompletionRequests() throws Exception {
        RecordedRequest first = mockLlm.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest second = mockLlm.takeRequest(5, TimeUnit.SECONDS);

        Assertions.assertThat(first.getMethod()).isEqualTo("POST");
        Assertions.assertThat(first.getPath()).isEqualTo("/v1/chat/completions");
        Assertions.assertThat(second.getMethod()).isEqualTo("POST");
        Assertions.assertThat(second.getPath()).isEqualTo("/v1/chat/completions");

        String firstMessages = messageContents(first);
        Assertions.assertThat(firstMessages).contains("operational incident fields");
        Assertions.assertThat(firstMessages).contains("JSON only");
        Assertions.assertThat(firstMessages).contains(INPUT);

        String secondMessages = messageContents(second);
        Assertions.assertThat(secondMessages).contains("classify operational incident JSON");
        Assertions.assertThat(secondMessages).contains(SUMMARIZER_JSON);
    }

    private static String messageContents(RecordedRequest request) throws IOException {
        JsonNode messages = JSON.readTree(request.getBody().readUtf8()).get("messages");
        StringBuilder joined = new StringBuilder();
        StreamSupport.stream(messages.spliterator(), false)
                .forEach(node -> joined.append(node.get("content").asText()).append('\n'));
        return joined.toString();
    }

    private void enqueueChatCompletion(String content) {
        try {
            mockLlm.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                    """
                                    {
                                      "choices": [
                                        {
                                          "message": {
                                            "content": %s
                                          }
                                        }
                                      ],
                                      "usage": {
                                        "prompt_tokens": 46,
                                        "completion_tokens": 17,
                                        "total_tokens": 63
                                      }
                                    }
                                    """
                                            .formatted(JSON.writeValueAsString(content))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException(e);
        }
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

    private RunResponse getRun(UUID runId) {
        return webTestClient
                .get()
                .uri("/api/runtime/runs/{runId}", runId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(RunResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private List<RuntimeEventResponse> fetchEventsAfterTerminal(UUID runId) throws InterruptedException {
        Thread.sleep(50);
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
        return events == null ? List.of() : new ArrayList<>(events);
    }

    private void awaitTerminal(UUID runId, RunStatus expected) {
        Duration timeout = Duration.ofSeconds(10);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            RunStatus status = getRun(runId).status();
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
        Assertions.fail("Run did not reach " + expected + " in time");
    }

    private static String toJsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private record ExpectedEvent(String type, String agentId) {}
}
