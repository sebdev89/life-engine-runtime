package io.lifeengine.runtime.api;

import io.lifeengine.runtime.app.RuntimeApplication;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Integration-level coverage for the Global Runtime Event Spine.
 *
 * <p>Security-disabled profile lets us focus on the multicast contract:
 *
 * <ul>
 *   <li>Subscribers see events from runs they had no prior knowledge of.
 *   <li>{@code workflowPrefix} filters out unrelated workflows.
 *   <li>The per-run stream still works alongside the global stream.
 * </ul>
 *
 * <p>The streams are kept open by design (no terminal completion at the global level — there's
 * no terminal for "all of life-engine"). Tests use {@code take(N)} to pull exactly the events
 * they care about and rely on the test client's cancellation to tear down the subscription.
 */
@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class GlobalRuntimeEventsControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void streamGlobal_emitsEventsFromAnyRun() throws Exception {
        // Subscribe FIRST so the multicast sink delivers events from the run we trigger below.
        // The global sink has no replay; subscribers only see events that arrive AFTER they
        // subscribe, so the start-run call must happen *after* the subscription is live.
        var subscription =
                webTestClient
                        .get()
                        .uri("/api/runtime/events/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectHeader()
                        .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                        .returnResult(RuntimeEventResponse.class)
                        .getResponseBody()
                        .filter(e -> e.runId() != null)
                        .take(1)
                        .collectList();

        // Trigger a fast no-LLM run on a separate scheduler so the subscribe-then-publish
        // ordering above is preserved. ~50ms is enough; the no-LLM workflow finishes in <300ms.
        Thread.sleep(150);
        UUID runId = createNoLlmRun();

        List<RuntimeEventResponse> events = subscription.block(Duration.ofSeconds(5));
        org.assertj.core.api.Assertions.assertThat(events).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(events.get(0).runId()).isEqualTo(runId);
    }

    @Test
    void streamGlobal_workflowPrefixFiltersOutUnrelatedRuns() throws Exception {
        // Filter for crypto.* events — a demo.no-llm.workflow run must NOT show up.
        var subscription =
                webTestClient
                        .get()
                        .uri(uri -> uri.path("/api/runtime/events/stream")
                                .queryParam("workflowPrefix", "demo.")
                                .build())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .returnResult(RuntimeEventResponse.class)
                        .getResponseBody()
                        .filter(e -> e.runId() != null)
                        .take(1)
                        .collectList();

        Thread.sleep(150);
        UUID runId = createNoLlmRun();

        // We only subscribed to demo.* — that's where this run lives, so we DO see at least one
        // event from it. The negative case (crypto subscriber sees nothing from a demo run) is
        // exercised in {@link #streamGlobal_workflowPrefixFiltersOutMismatchedWorkflow}.
        List<RuntimeEventResponse> events = subscription.block(Duration.ofSeconds(5));
        org.assertj.core.api.Assertions.assertThat(events).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(events.get(0).runId()).isEqualTo(runId);
        org.assertj.core.api.Assertions.assertThat(events.get(0).workflowId())
                .startsWith("demo.");
    }

    @Test
    void streamGlobal_workflowPrefixFiltersOutMismatchedWorkflow() throws Exception {
        // Crypto-prefixed subscriber must not receive events from a demo-prefixed run.
        var subscription =
                webTestClient
                        .get()
                        .uri(uri -> uri.path("/api/runtime/events/stream")
                                .queryParam("workflowPrefix", "crypto.")
                                .build())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .returnResult(RuntimeEventResponse.class)
                        .getResponseBody()
                        .filter(e -> e.runId() != null)
                        .take(1)
                        .timeout(Duration.ofMillis(800))
                        .onErrorResume(java.util.concurrent.TimeoutException.class, ex -> reactor.core.publisher.Flux.empty())
                        .collectList();

        Thread.sleep(150);
        createNoLlmRun();

        List<RuntimeEventResponse> events = subscription.block(Duration.ofSeconds(5));
        org.assertj.core.api.Assertions.assertThat(events).isEmpty();
    }

    @Test
    void perRunStream_stillWorksAlongsideGlobalStream() {
        UUID runId = createNoLlmRun();

        List<RuntimeEventResponse> events =
                webTestClient
                        .get()
                        .uri("/api/runtime/runs/{runId}/stream", runId)
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
        org.assertj.core.api.Assertions.assertThat(events.get(5).terminal()).isTrue();
    }

    private UUID createNoLlmRun() {
        return webTestClient
                .post()
                .uri("/api/runtime/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        "{\"workflowId\":\"demo.no-llm.workflow\",\"input\":\"global-spine-test\","
                                + "\"correlationId\":\"global-spine\"}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(RunResponse.class)
                .returnResult()
                .getResponseBody()
                .runId();
    }
}
