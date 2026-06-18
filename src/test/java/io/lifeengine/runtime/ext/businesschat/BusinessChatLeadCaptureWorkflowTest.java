package io.lifeengine.runtime.ext.businesschat;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@TestPropertySource(properties = "lifeengine.runtime.ext.business-chat.lead-capture.enabled=true")
class BusinessChatLeadCaptureWorkflowTest {

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
        String mockUrl = mockLlm.url("/").toString().replaceAll("/$", "");
        registry.add("runtime.llm.base-url", () -> mockUrl);
        registry.add("runtime.llm.model", () -> "test-model");
        registry.add("runtime.llm.api-key", () -> "test-key");
        // Phase 3: BusinessReplyAgent uses chatLlmClient — point it at the same mock.
        registry.add("runtime.llm.chat.base-url", () -> mockUrl);
    }

    @Test
    void workflowRunsLeadCaptureStage_andEnrichesContext() {
        enqueueLlm(contextResponseJson());
        enqueueLlm(leadCaptureResponseJson());
        enqueueLlm(replyResponseJson());

        UUID runId = startBusinessChatRun();
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<RuntimeEventResponse> events = collectEvents(runId);
        List<String> stageIds =
                events.stream()
                        .filter(e -> "STAGE_STARTED".equals(e.type()))
                        .map(RuntimeEventResponse::stageId)
                        .toList();
        Assertions.assertThat(stageIds)
                .containsExactly(
                        BusinessChatReplyModule.STAGE_BUSINESS_CONTEXT,
                        BusinessChatReplyModule.STAGE_LEAD_CAPTURE,
                        BusinessChatReplyModule.STAGE_BUSINESS_REPLY);

        com.fasterxml.jackson.databind.JsonNode leadStage = stageOutput(runId, BusinessChatReplyModule.STAGE_LEAD_CAPTURE);
        Assertions.assertThat(leadStage.get("leadCaptured").asBoolean()).isTrue();
        Assertions.assertThat(leadStage.get("leadData").get("nombre").asText()).isEqualTo("María López");
        Assertions.assertThat(leadStage.get("leadData").get("telefono").asText()).isEqualTo("11 4444-9999");
        Assertions.assertThat(leadStage.get("leadData").get("email").isNull()).isTrue();

        com.fasterxml.jackson.databind.JsonNode replyStage =
                stageOutput(runId, BusinessChatReplyModule.STAGE_BUSINESS_REPLY);
        Assertions.assertThat(replyStage.get("leadCaptured").asBoolean()).isTrue();
    }

    private com.fasterxml.jackson.databind.JsonNode stageOutput(UUID runId, String stageId) {
        com.fasterxml.jackson.databind.JsonNode detail = runDetail(runId);
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

    private com.fasterxml.jackson.databind.JsonNode runDetail(UUID runId) {
        return webTestClient
                .get()
                .uri("/api/runtime/runs/{runId}", runId)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(com.fasterxml.jackson.databind.JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private UUID startBusinessChatRun() {
        return webTestClient
                .post()
                .uri("/api/runtime/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        """
                        {
                          "workflowId":"business-chat.reply.v1",
                          "input":"{\\"channel\\":\\"WEB_CHAT\\",\\"botId\\":\\"barberia-demo\\",\\"conversationId\\":\\"conv-1\\",\\"customer\\":{\\"name\\":\\"Cliente Demo\\",\\"externalId\\":\\"web-demo-1\\"},\\"message\\":\\"Quiero turno, soy María López, mi teléfono es 11 4444-9999\\"}",
                          "correlationId":"business-chat-lead-test"
                        }
                        """)
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
        com.fasterxml.jackson.databind.JsonNode detail = runDetail(runId);
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
                  "intent": "booking",
                  "confidence": "HIGH",
                  "handoffRequired": false,
                  "leadCaptured": false,
                  "contextNotes": "Customer wants an appointment and shared contact details."
                }
                """;
    }

    private static String leadCaptureResponseJson() {
        return """
                {
                  "leadCaptured": true,
                  "leadData": {
                    "nombre": "María López",
                    "telefono": "11 4444-9999",
                    "email": null
                  }
                }
                """;
    }

    private static String replyResponseJson() {
        return """
                {
                  "response": "Perfecto María, anoto tu teléfono y te confirmo el turno.",
                  "intent": "booking",
                  "confidence": "HIGH",
                  "handoffRequired": false,
                  "leadCaptured": true,
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
