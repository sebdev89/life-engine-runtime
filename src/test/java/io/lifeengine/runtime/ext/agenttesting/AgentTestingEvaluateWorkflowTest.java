package io.lifeengine.runtime.ext.agenttesting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.api.RunResponse;
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
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class AgentTestingEvaluateWorkflowTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Autowired private WebTestClient webTestClient;

    @Test
    void workflowIsRegisteredOnBoot() {
        List<io.lifeengine.runtime.api.WorkflowListView> workflows =
                webTestClient
                        .get()
                        .uri("/api/runtime/workflows")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBodyList(io.lifeengine.runtime.api.WorkflowListView.class)
                        .returnResult()
                        .getResponseBody();

        Assertions.assertThat(workflows)
                .isNotNull()
                .anyMatch(w -> AgentTestingModule.EVALUATE_WORKFLOW_ID.equals(w.workflowId()));
    }

    @Test
    void evaluateWorkflowScoresNoHandoffScenarioPass() {
        UUID runId = startEvaluateRun(noHandoffInput());
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        JsonNode output = evaluateStageOutput(runId);
        Assertions.assertThat(output.path("verdict").asText()).isEqualTo("PASS");
        Assertions.assertThat(output.path("score").asInt()).isEqualTo(100);
        Assertions.assertThat(output.path("evaluator").asText()).isEqualTo("runtime-deterministic-v1");
        Assertions.assertThat(output.path("assertionResults"))
                .anyMatch(
                        node ->
                                "handoff_correctness".equals(node.path("dimension").asText())
                                        && node.path("passed").asBoolean());
    }

    private static String noHandoffInput() {
        return """
                {
                  "scenarioId": "legal-normal-faq",
                  "tenantId": "legal",
                  "transcript": [
                    {
                      "turn": 1,
                      "userMessage": "hola",
                      "botResponse": "bienvenido",
                      "handoffRequired": false,
                      "leadCaptured": true,
                      "lead": {"temperature": "WARM"},
                      "handoff": {}
                    }
                  ],
                  "assertions": [
                    {"dimension": "handoff_correctness", "expectedHandoff": false}
                  ],
                  "operatorOutcome": {"delivered": false, "content": null, "messageId": null}
                }
                """;
    }

    private UUID startEvaluateRun(String workflowInput) {
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
                              "correlationId":"atp-evaluate-test"
                            }
                            """
                                    .formatted(AgentTestingModule.EVALUATE_WORKFLOW_ID, escaped))
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

    private JsonNode evaluateStageOutput(UUID runId) {
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
            if (AgentTestingModule.STAGE_EVALUATE.equals(stage.path("stageId").asText())) {
                try {
                    return JSON.readTree(stage.get("output").asText());
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
        Assertions.fail("Evaluate stage output not found for run %s", runId);
        return JSON.nullNode();
    }
}
