package io.lifeengine.runtime.extension;

import io.lifeengine.runtime.api.RunResponse;
import io.lifeengine.runtime.api.RuntimeEventResponse;
import io.lifeengine.runtime.app.RuntimeApplication;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.tools.DemoEchoTool;
import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowStage;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@Import(PluggableWorkflowWebFluxTest.EchoWorkflowModule.class)
class PluggableWorkflowWebFluxTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void runtimeModuleRegisteredWorkflow_executesToolStages() {
        UUID runId = startEchoRun("ping");

        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<String> types = collectEventTypes(runId);
        Assertions.assertThat(types)
                .containsSubsequence(
                        List.of(
                                "RUN_STARTED",
                                "STAGE_STARTED",
                                "TOOL_STARTED",
                                "TOOL_SUCCEEDED",
                                "STAGE_SUCCEEDED",
                                "RUN_SUCCEEDED"));

        webTestClient
                .get()
                .uri("/api/runtime/runs/{runId}", runId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.metadata.executor")
                .isEqualTo("definition")
                .jsonPath("$.events[0].type")
                .isEqualTo("RUN_STARTED")
                .jsonPath("$.events[-1].terminal")
                .isEqualTo(true);
    }

    @TestConfiguration
    static class EchoWorkflowModule {
        @Bean
        RuntimeModule echoWorkflowModule() {
            return new RuntimeModule() {
                @Override
                public String moduleId() {
                    return "test-echo-module";
                }

                @Override
                public void register(RuntimeRegistry registry) {
                    registry.registerWorkflow(
                            new WorkflowDefinition(
                                    "test.echo.workflow",
                                    "runtime.text.v1",
                                    "runtime.text.v1",
                                    List.of(WorkflowStage.tool(DemoEchoTool.TOOL_ID, 1)),
                                    null,
                                    "Tool-only workflow registered via RuntimeModule"));
                }
            };
        }
    }

    private UUID startEchoRun(String input) {
        return webTestClient
                .post()
                .uri("/api/runtime/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        """
                        {"workflowId":"test.echo.workflow","input":"%s","correlationId":"module-test"}
                        """
                                .formatted(input))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(RunResponse.class)
                .returnResult()
                .getResponseBody()
                .runId();
    }

    private void awaitTerminal(UUID runId, RunStatus expected) {
        Duration timeout = Duration.ofSeconds(5);
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
        Assertions.fail("Run did not reach " + expected + " in time");
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
                        .block(Duration.ofSeconds(5));
        return events == null
                ? List.of()
                : events.stream().map(RuntimeEventResponse::type).toList();
    }
}
