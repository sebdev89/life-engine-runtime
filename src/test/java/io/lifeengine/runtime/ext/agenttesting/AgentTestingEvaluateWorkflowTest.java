package io.lifeengine.runtime.ext.agenttesting;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.api.RunResponse;
import io.lifeengine.runtime.api.RuntimeEventResponse;
import io.lifeengine.runtime.api.WorkflowListView;
import io.lifeengine.runtime.app.RuntimeApplication;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.ext.agenttesting.stages.AgentTestingEvaluateAgent;
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
                .anyMatch(w -> AgentTestingEvaluateModule.WORKFLOW_ID.equals(w.workflowId()));
    }

    @Test
    void evaluateWorkflow_runsDeterministicStage() throws Exception {
        String input =
                """
                {
                  "scenarioId": "legal-normal-faq",
                  "transcript": [
                    {"turn": 1, "handoffRequired": false, "leadCaptured": true, "lead": {}, "intent": "faq", "botResponse": "ok"}
                  ],
                  "assertions": [
                    {"dimension": "handoff_correctness", "expectedHandoff": false}
                  ],
                  "operatorOutcome": {"delivered": false}
                }
                """;

        UUID runId = startEvaluateRun(input);
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<RuntimeEventResponse> events = collectEvents(runId);
        Assertions.assertThat(events.stream().map(RuntimeEventResponse::type))
                .contains("AGENT_SUCCEEDED", "RUN_SUCCEEDED");
    }

    private UUID startEvaluateRun(String inputJson) throws Exception {
        String body =
                """
                {"workflowId":"%s","input":%s,"correlationId":"atp-test"}
                """
                        .formatted(
                                AgentTestingEvaluateModule.WORKFLOW_ID,
                                JSON.writeValueAsString(inputJson));

        RunResponse response =
                webTestClient
                        .post()
                        .uri("/api/runtime/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(RunResponse.class)
                        .returnResult()
                        .getResponseBody();

        Assertions.assertThat(response).isNotNull();
        return response.runId();
    }

    private void awaitTerminal(UUID runId, RunStatus expected) {
        for (int i = 0; i < 50; i++) {
            RunResponse detail =
                    webTestClient
                            .get()
                            .uri("/api/runtime/runs/{runId}", runId)
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody(RunResponse.class)
                            .returnResult()
                            .getResponseBody();
            if (detail != null && detail.status() == expected) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        Assertions.fail("Run " + runId + " did not reach " + expected);
    }

    private List<RuntimeEventResponse> collectEvents(UUID runId) {
        return webTestClient
                .get()
                .uri("/api/runtime/runs/{runId}/events", runId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(RuntimeEventResponse.class)
                .returnResult()
                .getResponseBody();
    }
}
