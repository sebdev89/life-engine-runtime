package io.lifeengine.runtime.ext.businesschat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.api.RunResponse;
import io.lifeengine.runtime.api.RuntimeEventResponse;
import io.lifeengine.runtime.api.WorkflowListView;
import io.lifeengine.runtime.app.RuntimeApplication;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.ext.businesschat.stages.BusinessContextAgent;
import io.lifeengine.runtime.ext.businesschat.stages.BusinessReplyAgent;
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
class BusinessChatReplyWorkflowTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private static MockWebServer mockLlm;

    @Autowired private WebTestClient webTestClient;

    @Autowired private BusinessConversationContext conversationContext;

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
                .anyMatch(w -> BusinessChatReplyModule.WORKFLOW_ID.equals(w.workflowId()));
    }

    @Test
    void businessChatReplyWorkflow_runsBothStages_andEmitsFullSequence() {
        enqueueLlm(contextResponseJson());
        enqueueLlm(replyResponseJson());

        UUID runId = startBusinessChatRun();
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<RuntimeEventResponse> events = collectEvents(runId);
        List<String> types = events.stream().map(RuntimeEventResponse::type).toList();

        Assertions.assertThat(types).contains("RUN_STARTED", "RUN_SUCCEEDED");
        Assertions.assertThat(types.stream().filter("STAGE_STARTED"::equals).count()).isEqualTo(2);
        Assertions.assertThat(types.stream().filter("STAGE_SUCCEEDED"::equals).count()).isEqualTo(2);
        Assertions.assertThat(types.stream().filter("AGENT_SUCCEEDED"::equals).count()).isEqualTo(2);
        Assertions.assertThat(types.stream().filter("LLM_CALL_STARTED"::equals).count()).isEqualTo(2);
        Assertions.assertThat(types.stream().filter("LLM_CALL_SUCCEEDED"::equals).count()).isEqualTo(2);
        Assertions.assertThat(types).contains("BUSINESS_CHAT_STARTED", "BUSINESS_CHAT_RESPONDED");
        Assertions.assertThat(types).doesNotContain("BUSINESS_CHAT_HANDOFF");

        List<String> stageIds =
                events.stream()
                        .filter(e -> "STAGE_STARTED".equals(e.type()))
                        .map(RuntimeEventResponse::stageId)
                        .toList();
        Assertions.assertThat(stageIds)
                .containsExactly(
                        BusinessChatReplyModule.STAGE_BUSINESS_CONTEXT,
                        BusinessChatReplyModule.STAGE_BUSINESS_REPLY);

        Map<String, String> expectedTemplateIdByAgent =
                Map.of(
                        BusinessContextAgent.AGENT_ID, BusinessChatReplyPrompts.CONTEXT_ID,
                        BusinessReplyAgent.AGENT_ID, BusinessChatReplyPrompts.REPLY_ID);
        for (RuntimeEventResponse ev :
                events.stream()
                        .filter(
                                e ->
                                        "LLM_CALL_STARTED".equals(e.type())
                                                || "LLM_CALL_SUCCEEDED".equals(e.type()))
                        .toList()) {
            Assertions.assertThat(ev.payload().get("promptTemplateId"))
                    .isEqualTo(expectedTemplateIdByAgent.get(ev.agentId()));
            Assertions.assertThat(ev.payload().get("promptTemplateVersion"))
                    .isEqualTo(BusinessChatReplyPrompts.VERSION_V1);
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
        com.fasterxml.jackson.databind.JsonNode replyStage = null;
        for (com.fasterxml.jackson.databind.JsonNode stage : detail.get("agentStages")) {
            if (BusinessChatReplyModule.STAGE_BUSINESS_REPLY.equals(stage.path("stageId").asText())) {
                replyStage = stage;
                break;
            }
        }
        Assertions.assertThat(replyStage).isNotNull();
        Assertions.assertThat(replyStage.get("status").asText()).isEqualTo("SUCCEEDED");
        try {
            com.fasterxml.jackson.databind.JsonNode parsed =
                    JSON.readTree(replyStage.get("output").asText());
            Assertions.assertThat(parsed.get("response").asText()).contains("12000");
            Assertions.assertThat(parsed.get("intent").asText()).isEqualTo("pricing");
            Assertions.assertThat(parsed.get("confidence").asText()).isEqualTo("HIGH");
            Assertions.assertThat(parsed.get("channel").asText()).isEqualTo("WEB_CHAT");
            Assertions.assertThat(parsed.get("handoffRequired").asBoolean()).isFalse();
            Assertions.assertThat(parsed.get("leadCaptured").asBoolean()).isFalse();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void businessChatReplyWorkflow_resolvesBotById() {
        enqueueLlm(contextResponseJson());
        enqueueLlm(replyResponseJson());

        UUID runId = startBusinessChatRun("inmobiliaria-demo", "¿Cuánto sale un 2 ambientes en venta?");
        awaitTerminal(runId, RunStatus.SUCCEEDED);

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
        com.fasterxml.jackson.databind.JsonNode contextStage = null;
        for (com.fasterxml.jackson.databind.JsonNode stage : detail.get("agentStages")) {
            if (BusinessChatReplyModule.STAGE_BUSINESS_CONTEXT.equals(stage.path("stageId").asText())) {
                contextStage = stage;
                break;
            }
        }
        Assertions.assertThat(contextStage).isNotNull();
        try {
            com.fasterxml.jackson.databind.JsonNode parsed =
                    JSON.readTree(contextStage.get("output").asText());
            Assertions.assertThat(parsed.get("botId").asText()).isEqualTo("inmobiliaria-demo");
            Assertions.assertThat(parsed.get("businessName").asText()).isEqualTo("Inmobiliaria Demo");
            Assertions.assertThat(parsed.get("knowledgeBase").asText()).contains("USD 120.000");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void businessChatReplyWorkflow_usesConversationHistoryOnFollowUp() {
        String conversationId = "conv-memory-" + UUID.randomUUID();

        enqueueLlm(contextResponseJson());
        enqueueLlm(replyResponseJson());
        UUID firstRunId =
                startBusinessChatRun(
                        "barberia-demo", "¿Cuánto sale un corte?", conversationId);
        awaitTerminal(firstRunId, RunStatus.SUCCEEDED);

        enqueueLlm(contextResponseJson());
        enqueueLlm(replyResponseJson());
        UUID secondRunId =
                startBusinessChatRun("barberia-demo", "¿Y el combo?", conversationId);
        awaitTerminal(secondRunId, RunStatus.SUCCEEDED);

        com.fasterxml.jackson.databind.JsonNode contextStage =
                contextStageOutput(secondRunId);
        Assertions.assertThat(contextStage.get("conversationHistory")).isNotNull();
        Assertions.assertThat(contextStage.get("conversationHistory")).hasSize(1);
        Assertions.assertThat(contextStage.get("conversationHistory").get(0).get("customerMessage").asText())
                .isEqualTo("¿Cuánto sale un corte?");
        Assertions.assertThat(contextStage.get("conversationHistory").get(0).get("botResponse").asText())
                .contains("12000");

        Assertions.assertThat(conversationContext.history(conversationId)).hasSize(2);
    }

    @Test
    void businessChatReplyWorkflow_triggersHandoffForFrustration() {
        enqueueLlm(
                """
                {
                  "intent": "complaint",
                  "confidence": "HIGH",
                  "handoffRequired": false,
                  "leadCaptured": false,
                  "contextNotes": "Customer frustrated."
                }
                """);
        enqueueLlm(
                """
                {
                  "response": "Te derivo con un asesor humano.",
                  "intent": "complaint",
                  "confidence": "HIGH",
                  "handoffRequired": false,
                  "leadCaptured": false,
                  "channel": "WEB_CHAT"
                }
                """);

        UUID runId =
                startBusinessChatRun(
                        "barberia-demo",
                        "Esto no sirve, quiero hablar con una persona real",
                        "conv-handoff-" + UUID.randomUUID());
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        com.fasterxml.jackson.databind.JsonNode contextStage = contextStageOutput(runId);
        Assertions.assertThat(contextStage.get("handoffRequired").asBoolean()).isTrue();
        Assertions.assertThat(contextStage.get("handoffReason").asText())
                .isEqualTo("FRUSTRATION");

        com.fasterxml.jackson.databind.JsonNode replyStage =
                stageOutput(runId, BusinessChatReplyModule.STAGE_BUSINESS_REPLY);
        Assertions.assertThat(replyStage.get("handoffRequired").asBoolean()).isTrue();

        List<String> eventTypes =
                collectEvents(runId).stream().map(RuntimeEventResponse::type).toList();
        Assertions.assertThat(eventTypes).contains("BUSINESS_CHAT_STARTED", "BUSINESS_CHAT_HANDOFF", "BUSINESS_CHAT_RESPONDED");
    }

    private com.fasterxml.jackson.databind.JsonNode stageOutput(UUID runId, String stageId) {
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
        for (com.fasterxml.jackson.databind.JsonNode stage : detail.get("agentStages")) {
            if (stageId.equals(stage.path("stageId").asText())) {
                try {
                    return JSON.readTree(stage.get("output").asText());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        throw new AssertionError("Stage not found: " + stageId);
    }

    private com.fasterxml.jackson.databind.JsonNode contextStageOutput(UUID runId) {
        return stageOutput(runId, BusinessChatReplyModule.STAGE_BUSINESS_CONTEXT);
    }

    private UUID startBusinessChatRun() {
        return startBusinessChatRun("barberia-demo", "Hola, cuánto sale corte y barba?", "conv-1");
    }

    private UUID startBusinessChatRun(String botId, String message) {
        return startBusinessChatRun(botId, message, "conv-1");
    }

    private UUID startBusinessChatRun(String botId, String message, String conversationId) {
        String escapedMessage = message.replace("\\", "\\\\").replace("\"", "\\\"");
        String escapedConversationId =
                conversationId.replace("\\", "\\\\").replace("\"", "\\\"");
        return webTestClient
                .post()
                .uri("/api/runtime/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        """
                        {
                          "workflowId":"business-chat.reply.v1",
                          "input":"{\\"channel\\":\\"WEB_CHAT\\",\\"botId\\":\\"%s\\",\\"conversationId\\":\\"%s\\",\\"customer\\":{\\"name\\":\\"Cliente Demo\\",\\"externalId\\":\\"web-demo-1\\"},\\"message\\":\\"%s\\"}",
                          "correlationId":"business-chat-test"
                        }
                        """
                                .formatted(botId, escapedConversationId, escapedMessage))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(RunResponse.class)
                .returnResult()
                .getResponseBody()
                .runId();
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

    private static String contextResponseJson() {
        return """
                {
                  "intent": "pricing",
                  "confidence": "HIGH",
                  "handoffRequired": false,
                  "leadCaptured": false,
                  "contextNotes": "Customer asking about combo pricing."
                }
                """;
    }

    private static String replyResponseJson() {
        return """
                {
                  "response": "El combo corte + barba sale $12000.",
                  "intent": "pricing",
                  "confidence": "HIGH",
                  "handoffRequired": false,
                  "leadCaptured": false,
                  "channel": "WEB_CHAT"
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
                                        "prompt_tokens": 120,
                                        "completion_tokens": 60,
                                        "total_tokens": 180
                                      }
                                    }
                                    """
                                            .formatted(JSON.writeValueAsString(content))));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
