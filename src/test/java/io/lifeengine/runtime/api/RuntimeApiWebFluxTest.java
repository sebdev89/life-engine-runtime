package io.lifeengine.runtime.api;

import io.lifeengine.runtime.app.RuntimeApplication;
import io.lifeengine.runtime.domain.RunStatus;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class RuntimeApiWebFluxTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void actuatorHealthIsUp() {
        webTestClient
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    void createRun_unknownWorkflowId_returns400() {
        webTestClient
                .post()
                .uri("/api/runtime/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"workflowId\":\"demo-cockpit\"}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("unknown_workflow");
    }

    @Test
    void createRun_returnsCreatedWithRunningStatus() {
        webTestClient
                .post()
                .uri("/api/runtime/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"workflowId\":\"demo.no-llm.workflow\",\"correlationId\":\"test-corr\"}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.runId")
                .exists()
                .jsonPath("$.status")
                .isEqualTo("RUNNING")
                .jsonPath("$.workflowId")
                .isEqualTo("demo.no-llm.workflow")
                .jsonPath("$.correlationId")
                .isEqualTo("test-corr");
    }

    @Test
    void getRun_afterCreate_returnsRun() {
        UUID runId = createRunAndReturnId();

        webTestClient
                .get()
                .uri("/api/runtime/runs/{runId}", runId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.runId")
                .isEqualTo(runId.toString())
                .jsonPath("$.status")
                .exists();
    }

    @Test
    void getRun_unknownId_returns404() {
        webTestClient
                .get()
                .uri("/api/runtime/runs/{runId}", UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("not_found");
    }

    @Test
    void streamEvents_afterTerminal_replaysAllSixEvents() {
        UUID runId = createRunAndReturnId();
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<RuntimeEventResponse> events =
                webTestClient
                        .get()
                        .uri("/api/runtime/runs/{runId}/events", runId)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectHeader()
                        .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                        .returnResult(RuntimeEventResponse.class)
                        .getResponseBody()
                        .take(6)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        org.assertj.core.api.Assertions.assertThat(events).hasSize(6);
        org.assertj.core.api.Assertions.assertThat(events.get(0).type()).isEqualTo("RUN_STARTED");
        org.assertj.core.api.Assertions.assertThat(events.get(5).type()).isEqualTo("RUN_COMPLETED");
    }

    @Test
    void streamEvents_duringExecution_receivesLiveSequence() {
        UUID runId = createRunAndReturnId();

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
                        .take(6)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        org.assertj.core.api.Assertions.assertThat(events).hasSize(6);
        org.assertj.core.api.Assertions.assertThat(events.get(5).terminal()).isTrue();
    }

    @Test
    void createRun_noLlmWorkflow_metadataExecutorIsFake() {
        webTestClient
                .post()
                .uri("/api/runtime/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"workflowId\":\"demo.no-llm.workflow\"}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(RunResponse.class)
                .value(
                        run -> {
                            org.assertj.core.api.Assertions.assertThat(run.metadata().get("executor"))
                                    .isEqualTo("fake");
                            org.assertj.core.api.Assertions.assertThat(run.workflowId())
                                    .isEqualTo("demo.no-llm.workflow");
                        });
    }

    @Test
    void streamEvents_emitsFakeWorkflowSequence() {
        UUID runId = createRunAndReturnId();

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
                        .take(6)
                        .collectList()
                        .block(Duration.ofSeconds(5));

        org.assertj.core.api.Assertions.assertThat(events).hasSize(6);
        org.assertj.core.api.Assertions.assertThat(events.get(0).type()).isEqualTo("RUN_STARTED");
        org.assertj.core.api.Assertions.assertThat(events.get(1).type()).isEqualTo("AGENT_STARTED");
        org.assertj.core.api.Assertions.assertThat(events.get(1).attributes().get("agentId"))
                .isEqualTo("agent-a");
        org.assertj.core.api.Assertions.assertThat(events.get(2).type()).isEqualTo("AGENT_COMPLETED");
        org.assertj.core.api.Assertions.assertThat(events.get(3).type()).isEqualTo("AGENT_STARTED");
        org.assertj.core.api.Assertions.assertThat(events.get(4).type()).isEqualTo("AGENT_COMPLETED");
        org.assertj.core.api.Assertions.assertThat(events.get(5).type()).isEqualTo("RUN_COMPLETED");
        org.assertj.core.api.Assertions.assertThat(events.get(5).terminal()).isTrue();
    }

    @Test
    void cancelRun_transitionsToCancelled() throws InterruptedException {
        UUID runId = createRunAndReturnId();
        Thread.sleep(30);

        webTestClient
                .post()
                .uri("/api/runtime/runs/{runId}/cancel", runId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("CANCELLED");

        webTestClient
                .get()
                .uri("/api/runtime/runs/{runId}", runId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("CANCELLED");
    }

    @Test
    void cancelRun_afterTerminal_returns409() {
        UUID runId = createRunAndReturnId();
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        webTestClient
                .post()
                .uri("/api/runtime/runs/{runId}/cancel", runId)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("conflict");
    }

    private UUID createRunAndReturnId() {
        return webTestClient
                .post()
                .uri("/api/runtime/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"workflowId\":\"demo.no-llm.workflow\"}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(RunResponse.class)
                .returnResult()
                .getResponseBody()
                .runId();
    }

    private void awaitTerminal(UUID runId, RunStatus expected) {
        Duration timeout = Duration.ofSeconds(3);
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
