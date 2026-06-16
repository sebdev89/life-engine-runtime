package io.lifeengine.runtime.ext.devsearchsmoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.api.RunResponse;
import io.lifeengine.runtime.api.RuntimeEventResponse;
import io.lifeengine.runtime.api.WorkflowListView;
import io.lifeengine.runtime.app.RuntimeApplication;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.api.RuntimeToolsController;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
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
 * Integration smoke for Capability Layer Sprint 3a: search.web ToolExecutor end-to-end via
 * the DefinitionDrivenWorkflowExecutor TOOL stage path.
 *
 * <p>Uses mock provider (no external API call). Verifies:
 * <ul>
 *   <li>search.web is visible in GET /api/runtime/tools when enabled</li>
 *   <li>dev.search-smoke.v1 workflow is registered and runs to SUCCEEDED</li>
 *   <li>TOOL_STARTED and TOOL_SUCCEEDED events are emitted in order</li>
 *   <li>tool output JSON contains status=ok + non-empty results array</li>
 * </ul>
 */
@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class DevSearchSmokeWorkflowTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void enableSearchSmoke(DynamicPropertyRegistry registry) {
        registry.add("runtime.tools.search.enabled", () -> "true");
        registry.add("runtime.tools.search.provider", () -> "mock");
        registry.add("runtime.ext.dev-search-smoke.enabled", () -> "true");
    }

    // ── tool registration ─────────────────────────────────────────────────────

    @Test
    void searchWebTool_isRegisteredWhenEnabled() {
        List<RuntimeToolsController.ToolListView> tools =
                webTestClient
                        .get()
                        .uri("/api/runtime/tools")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBodyList(RuntimeToolsController.ToolListView.class)
                        .returnResult()
                        .getResponseBody();

        Assertions.assertThat(tools)
                .isNotNull()
                .anyMatch(t -> "search.web".equals(t.toolId()));
    }

    @Test
    void devSearchSmokeWorkflow_isRegisteredOnBoot() {
        List<WorkflowListView> workflows =
                webTestClient
                        .get()
                        .uri("/api/runtime/workflows")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBodyList(WorkflowListView.class)
                        .returnResult()
                        .getResponseBody();

        Assertions.assertThat(workflows)
                .isNotNull()
                .anyMatch(w -> DevSearchSmokeModule.WORKFLOW_ID.equals(w.workflowId()));
    }

    // ── end-to-end workflow run ───────────────────────────────────────────────

    @Test
    void devSearchSmoke_mockProvider_workflowSucceeds_withToolEvents() {
        UUID runId = startSmokeRun("spring boot reactive security");
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<RuntimeEventResponse> events = collectEvents(runId);
        List<String> types = events.stream().map(RuntimeEventResponse::type).toList();

        Assertions.assertThat(types).contains("RUN_STARTED", "RUN_SUCCEEDED");
        Assertions.assertThat(types).contains("STAGE_STARTED", "STAGE_SUCCEEDED");
        Assertions.assertThat(types).contains("TOOL_STARTED", "TOOL_SUCCEEDED");
        Assertions.assertThat(types).doesNotContain("TOOL_FAILED", "RUN_FAILED");
    }

    @Test
    void devSearchSmoke_mockProvider_toolOutputContainsResults() throws Exception {
        UUID runId = startSmokeRun("life engine architecture");
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        JsonNode runDetail = fetchRunDetail(runId);
        JsonNode stages = runDetail.get("agentStages");
        Assertions.assertThat(stages).isNotNull();
        Assertions.assertThat(stages.isArray()).isTrue();

        JsonNode searchStage = null;
        for (JsonNode stage : stages) {
            if (DevSearchSmokeModule.STAGE_SEARCH.equals(stage.path("stageId").asText())) {
                searchStage = stage;
                break;
            }
        }
        Assertions.assertThat(searchStage).isNotNull();
        Assertions.assertThat(searchStage.path("status").asText()).isEqualTo("SUCCEEDED");

        JsonNode output = JSON.readTree(searchStage.path("output").asText());
        Assertions.assertThat(output.path("status").asText()).isEqualTo("ok");
        Assertions.assertThat(output.path("provider").asText()).isEqualTo("mock");
        Assertions.assertThat(output.path("results").isArray()).isTrue();
        Assertions.assertThat(output.path("results").size()).isGreaterThan(0);
        Assertions.assertThat(output.path("results").get(0).path("url").asText()).isNotBlank();
    }

    @Test
    void devSearchSmoke_toolStageEventOrdering_isCorrect() {
        UUID runId = startSmokeRun("reactive streams backpressure");
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<RuntimeEventResponse> events = collectEvents(runId);
        List<String> types = events.stream().map(RuntimeEventResponse::type).toList();

        int stageStarted = types.indexOf("STAGE_STARTED");
        int toolStarted  = types.indexOf("TOOL_STARTED");
        int toolSucc     = types.indexOf("TOOL_SUCCEEDED");
        int stageSucc    = types.indexOf("STAGE_SUCCEEDED");
        int runSucc      = types.indexOf("RUN_SUCCEEDED");

        Assertions.assertThat(stageStarted).isLessThan(toolStarted);
        Assertions.assertThat(toolStarted).isLessThan(toolSucc);
        Assertions.assertThat(toolSucc).isLessThan(stageSucc);
        Assertions.assertThat(stageSucc).isLessThan(runSucc);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID startSmokeRun(String query) {
        String input = "{\"query\":\"" + query + "\",\"maxResults\":3}";
        try {
            String escapedInput = JSON.writeValueAsString(input);
            return webTestClient
                    .post()
                    .uri("/api/runtime/runs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {
                              "workflowId":"%s",
                              "input":%s,
                              "correlationId":"smoke-%s"
                            }
                            """.formatted(DevSearchSmokeModule.WORKFLOW_ID, escapedInput, UUID.randomUUID()))
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
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
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
            if (status == expected) return;
            try { Thread.sleep(40); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        Assertions.fail("Run did not reach " + expected + " within timeout");
    }

    private List<RuntimeEventResponse> collectEvents(UUID runId) {
        JsonNode detail = fetchRunDetail(runId);
        if (detail == null || !detail.has("events")) return List.of();
        try {
            return JSON.convertValue(
                    detail.get("events"),
                    JSON.getTypeFactory().constructCollectionType(List.class, RuntimeEventResponse.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode fetchRunDetail(UUID runId) {
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
