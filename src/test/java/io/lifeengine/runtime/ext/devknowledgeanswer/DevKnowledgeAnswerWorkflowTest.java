package io.lifeengine.runtime.ext.devknowledgeanswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.api.RunResponse;
import io.lifeengine.runtime.api.RuntimeEventResponse;
import io.lifeengine.runtime.api.WorkflowListView;
import io.lifeengine.runtime.app.RuntimeApplication;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.ext.devknowledgeanswer.stages.DevAnswerAgent;
import io.lifeengine.runtime.ext.devknowledgeanswer.stages.DevContextAgent;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
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

@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class DevKnowledgeAnswerWorkflowTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private static MockWebServer mockLlm;

    @Autowired private WebTestClient webTestClient;

    @BeforeAll
    static void startMocks() throws IOException {
        mockLlm = new MockWebServer();
        mockLlm.start();
    }

    @AfterAll
    static void stopMocks() throws IOException {
        if (mockLlm != null) {
            mockLlm.shutdown();
        }
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("runtime.llm.base-url", () -> mockLlm.url("/").toString().replaceAll("/$", ""));
        registry.add("runtime.llm.model", () -> "test-model");
        registry.add("runtime.llm.api-key", () -> "test-key");
    }

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
                .anyMatch(w -> DevKnowledgeAnswerModule.WORKFLOW_ID.equals(w.workflowId()));
    }

    @Test
    void devKnowledgeAnswerWorkflow_runsBothStages_andEmitsFullSequence() {
        enqueueLlm(answerResponseJson());

        UUID runId = startKnowledgeAnswerRun();
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<RuntimeEventResponse> events = collectEvents(runId);
        List<String> types = events.stream().map(RuntimeEventResponse::type).toList();

        Assertions.assertThat(types).contains("RUN_STARTED", "RUN_SUCCEEDED");
        Assertions.assertThat(types.stream().filter("STAGE_STARTED"::equals).count()).isEqualTo(2);
        Assertions.assertThat(types.stream().filter("STAGE_SUCCEEDED"::equals).count()).isEqualTo(2);
        Assertions.assertThat(types.stream().filter("AGENT_SUCCEEDED"::equals).count()).isEqualTo(2);
        Assertions.assertThat(types.stream().filter("LLM_CALL_STARTED"::equals).count()).isEqualTo(1);
        Assertions.assertThat(types.stream().filter("LLM_CALL_SUCCEEDED"::equals).count()).isEqualTo(1);

        List<String> stageIds =
                events.stream()
                        .filter(e -> "STAGE_STARTED".equals(e.type()))
                        .map(RuntimeEventResponse::stageId)
                        .toList();
        Assertions.assertThat(stageIds)
                .containsExactly(
                        DevKnowledgeAnswerModule.STAGE_CONTEXT, DevKnowledgeAnswerModule.STAGE_ANSWER);

        RuntimeEventResponse contextSucceeded =
                events.stream()
                        .filter(
                                e ->
                                        "AGENT_SUCCEEDED".equals(e.type())
                                                && DevContextAgent.AGENT_ID.equals(e.agentId()))
                        .findFirst()
                        .orElseThrow();
        Assertions.assertThat(contextSucceeded.payload().get("chunkCount")).isEqualTo("1");
        Assertions.assertThat(contextSucceeded.payload().get("hasEvidence")).isEqualTo("true");

        RuntimeEventResponse answerSucceeded =
                events.stream()
                        .filter(
                                e ->
                                        "AGENT_SUCCEEDED".equals(e.type())
                                                && DevAnswerAgent.AGENT_ID.equals(e.agentId()))
                        .findFirst()
                        .orElseThrow();
        Assertions.assertThat(answerSucceeded.payload().get("confidence")).isEqualTo("high");
        Assertions.assertThat(answerSucceeded.payload().get("sourcesCount")).isEqualTo("1");
        Assertions.assertThat(answerSucceeded.payload().get("hasEvidence")).isEqualTo("true");

        for (RuntimeEventResponse ev :
                events.stream()
                        .filter(
                                e ->
                                        "LLM_CALL_STARTED".equals(e.type())
                                                || "LLM_CALL_SUCCEEDED".equals(e.type()))
                        .toList()) {
            Assertions.assertThat(ev.payload().get("promptTemplateId"))
                    .isEqualTo(DevKnowledgeAnswerPrompts.ANSWER_ID);
            Assertions.assertThat(ev.payload().get("promptTemplateVersion"))
                    .isEqualTo(DevKnowledgeAnswerPrompts.VERSION_V1);
        }

        com.fasterxml.jackson.databind.JsonNode detail =
                webTestClient
                        .get()
                        .uri("/api/runtime/runs/{runId}", runId)
                        .accept(MediaType.APPLICATION_JSON)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(com.fasterxml.jackson.databind.JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        com.fasterxml.jackson.databind.JsonNode answerStage = null;
        for (com.fasterxml.jackson.databind.JsonNode stage : detail.get("agentStages")) {
            if (DevKnowledgeAnswerModule.STAGE_ANSWER.equals(stage.path("stageId").asText())) {
                answerStage = stage;
                break;
            }
        }
        Assertions.assertThat(answerStage).isNotNull();
        Assertions.assertThat(answerStage.get("status").asText()).isEqualTo("SUCCEEDED");
        try {
            com.fasterxml.jackson.databind.JsonNode parsed =
                    JSON.readTree(answerStage.get("output").asText());
            Assertions.assertThat(parsed.get("answer").asText()).contains("RuntimeJwtAuthenticationWebFilter");
            Assertions.assertThat(parsed.get("confidence").asText()).isEqualTo("high");
            Assertions.assertThat(parsed.get("sources").isArray()).isTrue();
            Assertions.assertThat(parsed.get("sources").size()).isGreaterThanOrEqualTo(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UUID startKnowledgeAnswerRun() {
        String workflowInput =
                """
                {
                  "question": "¿Dónde se valida el JWT?",
                  "knowledgeContext": {
                    "retrievedChunks": [
                      {
                        "documentId": "doc-1",
                        "chunkId": "chunk-1",
                        "title": "JWT validation",
                        "content": "El JWT se valida en RuntimeJwtAuthenticationWebFilter antes de ejecutar los handlers.",
                        "score": 0.91
                      }
                    ]
                  }
                }
                """;
        try {
            String escaped = JSON.writeValueAsString(workflowInput);
            return webTestClient
                    .post()
                    .uri("/api/runtime/runs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(
                            """
                            {
                              "workflowId":"dev.knowledge-answer.v1",
                              "input":%s,
                              "correlationId":"dev-ka-test"
                            }
                            """
                                    .formatted(escaped))
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
            if (status == expected) {
                return;
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        Assertions.fail("Run did not reach " + expected + " in time");
    }

    private List<RuntimeEventResponse> collectEvents(UUID runId) {
        com.fasterxml.jackson.databind.JsonNode detail =
                webTestClient
                        .get()
                        .uri("/api/runtime/runs/{runId}", runId)
                        .accept(MediaType.APPLICATION_JSON)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(com.fasterxml.jackson.databind.JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        if (detail == null || !detail.has("events")) {
            return List.of();
        }
        try {
            return JSON.convertValue(
                    detail.get("events"),
                    JSON.getTypeFactory().constructCollectionType(List.class, RuntimeEventResponse.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String answerResponseJson() {
        return """
                {
                  "answer": "El JWT se valida en RuntimeJwtAuthenticationWebFilter antes de ejecutar los handlers de ruta.",
                  "confidence": "high",
                  "sources": []
                }
                """;
    }

    private void enqueueLlm(String content) {
        try {
            mockLlm.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                    """
                                    {
                                      "choices": [
                                        {"message": {"content": %s}}
                                      ],
                                      "usage": {
                                        "prompt_tokens": 80,
                                        "completion_tokens": 40,
                                        "total_tokens": 120
                                      }
                                    }
                                    """
                                            .formatted(JSON.writeValueAsString(content))));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
