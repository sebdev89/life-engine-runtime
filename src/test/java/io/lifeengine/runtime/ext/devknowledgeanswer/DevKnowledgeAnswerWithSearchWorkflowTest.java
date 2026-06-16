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
 * Sprint 4b — search.web TOOL stage wired into dev.knowledge-answer.v1 (search-only path).
 *
 * <p>Verifies that when {@code runtime.tools.search.enabled=true} and rag is disabled:
 * <ul>
 *   <li>the workflow gains a {@code search-web} TOOL stage before dev-context</li>
 *   <li>DevContextAgent populates chunks from {@code ctx.toolOutputs["search.web"]}</li>
 *   <li>Stage order: search-web → dev-context → dev-answer</li>
 * </ul>
 */
@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class DevKnowledgeAnswerWithSearchWorkflowTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private static MockWebServer mockLlm;

    @Autowired
    private WebTestClient webTestClient;

    @BeforeAll
    static void startMocks() throws IOException {
        mockLlm = new MockWebServer();
        mockLlm.start();
    }

    @AfterAll
    static void stopMocks() throws IOException {
        if (mockLlm != null) mockLlm.shutdown();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("runtime.llm.base-url",
                () -> mockLlm.url("/").toString().replaceAll("/$", ""));
        registry.add("runtime.llm.model", () -> "test-model");
        registry.add("runtime.llm.api-key", () -> "test-key");
        registry.add("runtime.tools.search.enabled", () -> "true");
        registry.add("runtime.tools.search.provider", () -> "mock");
        registry.add("runtime.tools.rag.enabled", () -> "false");
    }

    @BeforeEach
    void enqueueLlm() throws Exception {
        String answerJson = "{\"answer\":\"Spring Boot simplifies web development.\",\"confidence\":\"medium\",\"sources\":[]}";
        mockLlm.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "choices": [{"message": {"content": %s}}],
                          "usage": {"prompt_tokens":80,"completion_tokens":40,"total_tokens":120}
                        }
                        """.formatted(JSON.writeValueAsString(answerJson))));
    }

    @Test
    void searchOnly_toolStageEventsPresent() {
        UUID runId = startRun();
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<String> types = collectEventTypes(runId);
        Assertions.assertThat(types).contains("TOOL_STARTED", "TOOL_SUCCEEDED", "RUN_SUCCEEDED");
        Assertions.assertThat(types).doesNotContain("TOOL_FAILED", "RUN_FAILED");
    }

    @Test
    void searchOnly_contextAgentUsesSearchChunks() throws Exception {
        UUID runId = startRun();
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        JsonNode detail = fetchDetail(runId);
        JsonNode contextStage = findStage(detail, DevKnowledgeAnswerModule.STAGE_CONTEXT);

        Assertions.assertThat(contextStage).isNotNull();
        JsonNode out = JSON.readTree(contextStage.path("output").asText());
        Assertions.assertThat(out.path("chunkCount").asInt())
                .as("DevContextAgent must populate chunks from search.web toolOutput")
                .isGreaterThan(0);
        Assertions.assertThat(out.path("hasEvidence").asBoolean()).isTrue();
    }

    @Test
    void searchOnly_stageOrderIsSearchThenContextThenAnswer() {
        UUID runId = startRun();
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<String> stageIds = collectEvents(runId).stream()
                .filter(e -> "STAGE_STARTED".equals(e.type()))
                .map(RuntimeEventResponse::stageId)
                .toList();

        Assertions.assertThat(stageIds).containsExactly(
                DevKnowledgeAnswerModule.STAGE_SEARCH,
                DevKnowledgeAnswerModule.STAGE_CONTEXT,
                DevKnowledgeAnswerModule.STAGE_ANSWER);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private UUID startRun() {
        String input = """
                {"question":"What is Spring Boot?","knowledgeContext":{"retrievedChunks":[]}}
                """;
        try {
            return webTestClient.post().uri("/api/runtime/runs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"workflowId":"dev.knowledge-answer.v1","input":%s,"correlationId":"search-4b-%s"}
                            """.formatted(JSON.writeValueAsString(input), UUID.randomUUID()))
                    .exchange().expectStatus().isCreated()
                    .expectBody(RunResponse.class).returnResult().getResponseBody().runId();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void awaitTerminal(UUID runId, RunStatus expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            RunStatus s = webTestClient.get().uri("/api/runtime/runs/{id}", runId)
                    .exchange().expectStatus().isOk()
                    .expectBody(RunResponse.class).returnResult().getResponseBody().status();
            if (s == expected) return;
            try { Thread.sleep(40); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new RuntimeException(e);
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
            return JSON.convertValue(detail.get("events"),
                    JSON.getTypeFactory().constructCollectionType(List.class, RuntimeEventResponse.class));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private JsonNode fetchDetail(UUID runId) {
        return webTestClient.get().uri("/api/runtime/runs/{id}", runId)
                .accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    private static JsonNode findStage(JsonNode detail, String stageId) {
        if (detail == null) return null;
        JsonNode stages = detail.get("agentStages");
        if (stages == null) return null;
        for (JsonNode s : stages) {
            if (stageId.equals(s.path("stageId").asText())) return s;
        }
        return null;
    }
}
