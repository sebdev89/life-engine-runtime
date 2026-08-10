package io.lifeengine.runtime.api;

import io.lifeengine.runtime.app.RuntimeApplication;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Semántica de {@code Last-Event-ID}, congelada en SPEC-009 §2ter.
 *
 * <p>Un id no numérico es {@code 400 validation_failed}, no un replay silencioso. Eso incluye el
 * UUID que mandaría un cliente anterior a F1 — la spec no define compatibilidad para ese caso, y
 * devolverle el run entero sería cambiar en el código una semántica ya aprobada.
 */
@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class RuntimeApiLastEventIdTest {

    @Autowired private WebTestClient webTestClient;

    private UUID startedRun() {
        return webTestClient
                .post()
                .uri("/api/runtime/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"workflowId\":\"demo.no-llm.workflow\",\"input\":\"last-event-id\"}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(RunResponse.class)
                .returnResult()
                .getResponseBody()
                .runId();
    }

    private WebTestClient.ResponseSpec streamWith(String lastEventId) {
        WebTestClient.RequestHeadersSpec<?> request =
                webTestClient
                        .get()
                        .uri("/api/runtime/runs/{runId}/stream", startedRun())
                        .accept(MediaType.TEXT_EVENT_STREAM);
        if (lastEventId != null) {
            request = request.header("Last-Event-ID", lastEventId);
        }
        return request.exchange();
    }

    @Test
    void nonNumericLastEventId_is400ValidationFailed() {
        streamWith("not-a-sequence")
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("validation_failed");
    }

    @Test
    void legacyUuidLastEventId_is400_becauseTheSpecDefinesNoCompatibility() {
        streamWith("5cb0f1e2-0000-4000-8000-000000000000")
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("validation_failed");
    }

    @Test
    void negativeLastEventId_is400() {
        streamWith("-1").expectStatus().isBadRequest();
    }

    @Test
    void absentLastEventId_isAccepted() {
        streamWith(null).expectStatus().isOk();
    }

    @Test
    void numericLastEventId_isAccepted() {
        streamWith("1").expectStatus().isOk();
    }
}
