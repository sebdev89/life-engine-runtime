package io.lifeengine.runtime.ext.multiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.api.RunResponse;
import io.lifeengine.runtime.api.RuntimeEventResponse;
import io.lifeengine.runtime.api.WorkflowListView;
import io.lifeengine.runtime.app.RuntimeApplication;
import io.lifeengine.runtime.domain.RunStatus;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class MultiAgentDelegateWorkflowTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Autowired private WebTestClient webTestClient;

    @Test
    void workflowIsRegisteredOnBoot() {
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
                .anyMatch(w -> MultiAgentModule.WORKFLOW_ID.equals(w.workflowId()));
    }

    @Test
    void legalHandoffDelegatesToLegalSpecialist() {
        UUID runId = startDelegateRun("legal", "handoff");
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<RuntimeEventResponse> events = collectEvents(runId);
        Assertions.assertThat(events.stream().map(RuntimeEventResponse::type))
                .contains("ROUTING_DECISION", "AGENT_SUCCEEDED", "RUN_SUCCEEDED");

        JsonNode output = delegateStageOutput(runId);
        Assertions.assertThat(output.path("specialistId").asText()).isEqualTo("legal-specialist");
        Assertions.assertThat(output.path("workflowId").asText()).isEqualTo("business-chat.reply.v1");
    }

    private UUID startDelegateRun(String tenantId, String intent) {
        String workflowInput =
                """
                {
                  "tenantId": "%s",
                  "intent": "%s"
                }
                """
                        .formatted(tenantId, intent);
        try {
            String escaped = JSON.writeValueAsString(workflowInput);
            return webTestClient
                    .post()
                    .uri("/api/runtime/runs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(
                            """
                            {
                              "workflowId":"%s",
                              "input":%s,
                              "correlationId":"multi-agent-delegate-test"
                            }
                            """
                                    .formatted(MultiAgentModule.WORKFLOW_ID, escaped))
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(RunResponse.class)
                    .returnResult()
                    .getResponseBody()
                    .runId();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
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
            if (status == expected) {
                return;
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ex);
            }
        }
        Assertions.fail("Run did not reach " + expected + " in time");
    }

    private List<RuntimeEventResponse> collectEvents(UUID runId) {
        JsonNode detail =
                webTestClient
                        .get()
                        .uri("/api/runtime/runs/{runId}", runId)
                        .accept(MediaType.APPLICATION_JSON)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        if (detail == null || !detail.has("events")) {
            return List.of();
        }
        try {
            return JSON.convertValue(
                    detail.get("events"),
                    JSON.getTypeFactory().constructCollectionType(List.class, RuntimeEventResponse.class));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private JsonNode delegateStageOutput(UUID runId) {
        JsonNode detail =
                webTestClient
                        .get()
                        .uri("/api/runtime/runs/{runId}", runId)
                        .accept(MediaType.APPLICATION_JSON)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        Assertions.assertThat(detail).isNotNull();
        for (JsonNode stage : detail.get("agentStages")) {
            if (MultiAgentModule.STAGE_DELEGATE.equals(stage.path("stageId").asText())) {
                try {
                    return JSON.readTree(stage.get("output").asText());
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
        Assertions.fail("Delegate stage output not found for run %s", runId);
        return JSON.nullNode();
    }
}
