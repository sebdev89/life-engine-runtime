package io.lifeengine.runtime.ext.devknowledgeanswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * Sprint 3b — Capability Layer: RagQueryTool wired into dev.knowledge-answer.v1.
 *
 * <p>Verifies that when {@code runtime.tools.rag.enabled=true}:
 * <ul>
 *   <li>the workflow gains a {@code rag-query} TOOL stage before dev-context</li>
 *   <li>TOOL_STARTED / TOOL_SUCCEEDED events are emitted</li>
 *   <li>DevContextAgent picks up chunks from {@code ctx.toolOutputs["rag.query"]} rather than
 *       from the (empty) {@code knowledgeContext.retrievedChunks} in the workflow input</li>
 * </ul>
 *
 * <p>Uses a separate Spring context from {@link DevKnowledgeAnswerWorkflowTest} because
 * {@code runtime.tools.rag.enabled} changes the workflow stage list at boot time.
 */
@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class DevKnowledgeAnswerWithRagWorkflowTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private static MockWebServer mockLlm;
    private static MockWebServer mockRag;

    @Autowired
    private WebTestClient webTestClient;

    @BeforeAll
    static void startMocks() throws IOException {
        mockLlm = new MockWebServer();
        mockLlm.start();
        mockRag = new MockWebServer();
        mockRag.start();
    }

    @AfterAll
    static void stopMocks() throws IOException {
        if (mockLlm != null) mockLlm.shutdown();
        if (mockRag != null) mockRag.shutdown();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("runtime.llm.base-url",
                () -> mockLlm.url("/").toString().replaceAll("/$", ""));
        registry.add("runtime.llm.model", () -> "test-model");
        registry.add("runtime.llm.api-key", () -> "test-key");
        registry.add("runtime.tools.rag.enabled", () -> "true");
        registry.add("runtime.tools.rag.base-url",
                () -> "http://localhost:" + mockRag.getPort());
        registry.add("runtime.tools.rag.default-collection-id", () -> "test-collection");
    }

    @BeforeEach
    void enqueueDefaultRagResponse() {
        mockRag.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"chunks":[
                          {"text":"JWT is validated in RuntimeJwtAuthenticationWebFilter",
                           "score":0.92,"citationId":"","documentId":"doc-1",
                           "chunkId":"chunk-1","title":"JWT Validation"}
                        ]}
                        """));
    }

    @BeforeEach
    void enqueueDefaultLlmResponse() throws Exception {
        String answerJson = """
                {"answer":"JWT is validated in the filter chain.","confidence":"high","sources":[]}
                """;
        mockLlm.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "choices": [{"message": {"content": %s}}],
                          "usage": {"prompt_tokens":80,"completion_tokens":40,"total_tokens":120}
                        }
                        """.formatted(JSON.writeValueAsString(answerJson.strip()))));
    }

    @Test
    void ragEnabled_toolStageEventsPresent() {
        UUID runId = startRun();
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<String> types = collectEventTypes(runId);

        Assertions.assertThat(types).contains("TOOL_STARTED", "TOOL_SUCCEEDED");
        Assertions.assertThat(types).contains("RUN_SUCCEEDED");
        Assertions.assertThat(types).doesNotContain("TOOL_FAILED", "RUN_FAILED");
    }

    @Test
    void ragEnabled_contextAgentUsesRagChunks() throws Exception {
        UUID runId = startRun();
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        JsonNode detail = fetchDetail(runId);
        JsonNode stages = detail.get("agentStages");
        Assertions.assertThat(stages).isNotNull();

        JsonNode contextStage = null;
        for (JsonNode stage : stages) {
            if (DevKnowledgeAnswerModule.STAGE_CONTEXT.equals(stage.path("stageId").asText())) {
                contextStage = stage;
                break;
            }
        }
        Assertions.assertThat(contextStage).isNotNull();
        Assertions.assertThat(contextStage.path("status").asText()).isEqualTo("SUCCEEDED");

        JsonNode out = JSON.readTree(contextStage.path("output").asText());
        Assertions.assertThat(out.path("chunkCount").asInt())
                .as("DevContextAgent must populate chunks from rag.query toolOutput, not empty input")
                .isGreaterThan(0);
        Assertions.assertThat(out.path("hasEvidence").asBoolean()).isTrue();
    }

    @Test
    void ragEnabled_stageOrderIsRagThenContextThenAnswer() {
        UUID runId = startRun();
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<RuntimeEventResponse> events = collectEvents(runId);
        List<String> stageIds = events.stream()
                .filter(e -> "STAGE_STARTED".equals(e.type()))
                .map(RuntimeEventResponse::stageId)
                .toList();

        Assertions.assertThat(stageIds)
                .containsExactly(
                        DevKnowledgeAnswerModule.STAGE_RAG,
                        DevKnowledgeAnswerModule.STAGE_CONTEXT,
                        DevKnowledgeAnswerModule.STAGE_ANSWER);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private UUID startRun() {
        String workflowInput =
                """
                {
                  "question": "What is JWT?",
                  "collectionId": "test-collection",
                  "knowledgeContext": {"retrievedChunks": []}
                }
                """;
        try {
            String escaped = JSON.writeValueAsString(workflowInput);
            return webTestClient
                    .post()
                    .uri("/api/runtime/runs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {
                              "workflowId":"dev.knowledge-answer.v1",
                              "input":%s,
                              "correlationId":"rag-3b-%s"
                            }
                            """.formatted(escaped, UUID.randomUUID()))
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(RunResponse.class)
                    .returnResult()
                    .getResponseBody()
                    .runId();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void awaitTerminal(UUID runId, RunStatus expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            RunStatus status = webTestClient
                    .get()
                    .uri("/api/runtime/runs/{runId}", runId)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(RunResponse.class)
                    .returnResult()
                    .getResponseBody()
                    .status();
            if (status == expected) return;
            try { Thread.sleep(40); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        Assertions.fail("Run did not reach " + expected + " within timeout");
    }

    private List<String> collectEventTypes(UUID runId) {
        return collectEvents(runId).stream().map(RuntimeEventResponse::type).toList();
    }

    private List<RuntimeEventResponse> collectEvents(UUID runId) {
        JsonNode detail = fetchDetail(runId);
        if (detail == null || !detail.has("events")) return List.of();
        try {
            return JSON.convertValue(
                    detail.get("events"),
                    JSON.getTypeFactory().constructCollectionType(List.class, RuntimeEventResponse.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode fetchDetail(UUID runId) {
        return webTestClient
                .get()
                .uri("/api/runtime/runs/{runId}", runId)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }
}
