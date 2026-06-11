package io.lifeengine.runtime.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.app.RuntimeApplication;
import io.lifeengine.runtime.ext.businesschat.BusinessChatObservabilityEvents;
import java.util.LinkedHashMap;
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
class RunAppendEventsControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Autowired private WebTestClient webTestClient;

    @Test
    void appendRunEvents_publishesAndDedupes() {
        UUID runId = startDemoRun();

        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("botId", "demo");
        attrs.put("conversationId", UUID.randomUUID().toString());
        attrs.put(BusinessChatObservabilityEvents.ATTR_DEDUP_KEY, "HANDOFF_DECISION:" + runId);
        attrs.put(BusinessChatObservabilityEvents.ATTR_SOURCE, "business-chat-service");
        attrs.put("handoffRequired", "true");
        attrs.put("handoffReason", "pide_humano");

        String body =
                """
                {
                  "events": [
                    {
                      "type": "HANDOFF_DECISION",
                      "attributes": %s,
                      "terminal": false
                    },
                    {
                      "type": "HANDOFF_DECISION",
                      "attributes": %s,
                      "terminal": false
                    }
                  ]
                }
                """
                        .formatted(toJson(attrs), toJson(attrs));

        webTestClient
                .post()
                .uri("/api/runtime/runs/{runId}/events", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isNoContent();

        List<RuntimeEventResponse> events = collectEvents(runId);
        long handoffCount =
                events.stream().filter(e -> "HANDOFF_DECISION".equals(e.type())).count();
        Assertions.assertThat(handoffCount).isEqualTo(1);
    }

    private UUID startDemoRun() {
        RunResponse response =
                webTestClient
                        .post()
                        .uri("/api/runtime/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(
                                """
                                {
                                  "workflowId": "demo.no-llm.workflow",
                                  "input": "hello",
                                  "correlationId": "append-test"
                                }
                                """)
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(RunResponse.class)
                        .returnResult()
                        .getResponseBody();
        Assertions.assertThat(response).isNotNull();
        return response.runId();
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

    private static String toJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append('"').append(entry.getKey()).append("\":\"").append(entry.getValue()).append('"');
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }
}
