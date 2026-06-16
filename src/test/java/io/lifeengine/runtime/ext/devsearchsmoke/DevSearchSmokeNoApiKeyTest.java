package io.lifeengine.runtime.ext.devsearchsmoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.api.RunResponse;
import io.lifeengine.runtime.api.RuntimeEventResponse;
import io.lifeengine.runtime.app.RuntimeApplication;
import io.lifeengine.runtime.domain.RunStatus;
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
 * Verifies the Tavily provider fallback path: when provider=tavily but no API key is set,
 * SearchWebTool returns status=disabled and the workflow still reaches SUCCEEDED.
 *
 * <p>This uses a separate Spring context from {@link DevSearchSmokeWorkflowTest} because the
 * {@code SearchProvider} bean is constructed at context start-time and can't be swapped
 * mid-test-class.
 */
@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class DevSearchSmokeNoApiKeyTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void tavilyNoKey(DynamicPropertyRegistry registry) {
        registry.add("runtime.tools.search.enabled", () -> "true");
        registry.add("runtime.tools.search.provider", () -> "tavily");
        registry.add("runtime.tools.search.tavily-api-key", () -> "");
        registry.add("runtime.ext.dev-search-smoke.enabled", () -> "true");
    }

    @Test
    void tavilyNoApiKey_workflowStillSucceeds() {
        UUID runId = startSmokeRun("spring boot");
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<RuntimeEventResponse> events = collectEvents(runId);
        List<String> types = events.stream().map(RuntimeEventResponse::type).toList();

        Assertions.assertThat(types).contains("RUN_SUCCEEDED");
        Assertions.assertThat(types).doesNotContain("RUN_FAILED");
    }

    @Test
    void tavilyNoApiKey_toolOutputHasDisabledStatus() throws Exception {
        UUID runId = startSmokeRun("testing resilience");
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        JsonNode detail = fetchRunDetail(runId);
        JsonNode stages = detail.get("agentStages");
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
        Assertions.assertThat(output.path("status").asText()).isEqualTo("disabled");
        Assertions.assertThat(output.path("results").size()).isEqualTo(0);
    }

    @Test
    void tavilyNoApiKey_noHttpCallToTavilyApi() {
        UUID runId = startSmokeRun("what is vectordb");
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<RuntimeEventResponse> events = collectEvents(runId);
        List<String> types = events.stream().map(RuntimeEventResponse::type).toList();

        Assertions.assertThat(types).contains("TOOL_SUCCEEDED");
        Assertions.assertThat(types).doesNotContain("TOOL_FAILED");
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
                              "correlationId":"smoke-nokey-%s"
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
