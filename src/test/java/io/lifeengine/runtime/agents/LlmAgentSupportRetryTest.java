package io.lifeengine.runtime.agents;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.runtime.core.InMemoryRunStore;
import io.lifeengine.runtime.core.RunStore;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.events.RunEventPublisher;
import io.lifeengine.runtime.llm.LlmCallException;
import io.lifeengine.runtime.llm.LlmCallRecord;
import io.lifeengine.runtime.llm.LlmClient;
import io.lifeengine.runtime.llm.LlmMessage;
import io.lifeengine.runtime.llm.LlmModelRole;
import io.lifeengine.runtime.llm.LlmRequest;
import io.lifeengine.runtime.llm.LlmResponse;
import io.lifeengine.runtime.llm.LlmRetryConfig;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Verifies the conservative LLM retry policy applied by {@link LlmAgentSupport}:
 *
 * <ul>
 *   <li>Transient transport/provider failures (HTTP 502/503/504, timeout, connection reset) are retried.
 *   <li>Permanent failures (HTTP 4xx, validation/parse errors) are NOT retried.
 *   <li>Each retry emits {@code WARNING_RECORDED}; the final failure still emits {@code LLM_CALL_FAILED}.
 *   <li>Token accounting reflects the successful final response only.
 * </ul>
 */
class LlmAgentSupportRetryTest {

    @Test
    void retries_on503_thenSuccess_emitsWarningAndFinalSuccess() {
        ScriptedLlmClient llm =
                new ScriptedLlmClient(new LlmRetryConfig(true, 2, 0L))
                        .thenFail(http503())
                        .thenSucceed(new LlmResponse(
                                "ok",
                                Map.of("prompt_tokens", 7, "completion_tokens", 11, "total_tokens", 18)));

        Harness h = harness();
        StepVerifier.create(
                        LlmAgentSupport.callLlm(
                                h.ctx, "stage-1", "agent-1", llm, List.of(new LlmMessage("user", "hi"))))
                .assertNext(
                        response -> {
                            assertThat(response.content()).isEqualTo("ok");
                            assertThat(response.usage().get("total_tokens")).isEqualTo(18);
                        })
                .verifyComplete();

        assertThat(llm.invocations()).isEqualTo(2);

        List<String> types = h.eventTypes();
        assertThat(types).contains("LLM_CALL_STARTED", "WARNING_RECORDED", "LLM_CALL_SUCCEEDED");
        assertThat(types).doesNotContain("LLM_CALL_FAILED");
        assertThat(h.warnings()).hasSize(1);
        assertThat(h.warnings().get(0)).contains("retry 1/2").contains("status=503");

        List<io.lifeengine.runtime.llm.LlmCallRecord> records =
                h.store.llmCallRecordsFor(h.runId);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).rawResponse()).isEqualTo("ok");
        assertThat(records.get(0).parseError()).isNull();
    }

    @Test
    void doesNotRetry_on400_emitsLlmCallFailedOnce() {
        ScriptedLlmClient llm =
                new ScriptedLlmClient(new LlmRetryConfig(true, 2, 0L))
                        .thenFail(http400());

        Harness h = harness();
        StepVerifier.create(
                        LlmAgentSupport.callLlm(
                                h.ctx, "stage-1", "agent-1", llm, List.of(new LlmMessage("user", "hi"))))
                .expectErrorSatisfies(
                        err -> {
                            assertThat(err).isInstanceOf(LlmCallException.class);
                            assertThat(((LlmCallException) err).statusCode()).isEqualTo(400);
                        })
                .verify();

        assertThat(llm.invocations()).isEqualTo(1);

        List<String> types = h.eventTypes();
        assertThat(types).contains("LLM_CALL_STARTED", "LLM_CALL_FAILED");
        assertThat(types).doesNotContain("WARNING_RECORDED");
        assertThat(h.warnings()).isEmpty();
    }

    @Test
    void retriesThenFails_onTimeout_emitsWarningsThenLlmCallFailed() {
        ScriptedLlmClient llm =
                new ScriptedLlmClient(new LlmRetryConfig(true, 2, 0L))
                        .thenFail(timeout())
                        .thenFail(timeout())
                        .thenFail(timeout());

        Harness h = harness();
        StepVerifier.create(
                        LlmAgentSupport.callLlm(
                                h.ctx, "stage-1", "agent-1", llm, List.of(new LlmMessage("user", "hi"))))
                .expectErrorSatisfies(
                        err -> {
                            assertThat(err).isInstanceOf(LlmCallException.class);
                            assertThat(((LlmCallException) err).statusCode()).isNull();
                            assertThat(((LlmCallException) err).isTransient()).isTrue();
                        })
                .verify();

        assertThat(llm.invocations()).isEqualTo(3);

        List<String> types = h.eventTypes();
        assertThat(types).contains("LLM_CALL_STARTED", "WARNING_RECORDED", "LLM_CALL_FAILED");
        assertThat(types).doesNotContain("LLM_CALL_SUCCEEDED");
        assertThat(h.warnings()).hasSize(2);
        assertThat(h.warnings().get(0)).contains("retry 1/2");
        assertThat(h.warnings().get(1)).contains("retry 2/2");

        long failedCount = types.stream().filter("LLM_CALL_FAILED"::equals).count();
        assertThat(failedCount).isEqualTo(1);
    }

    @Test
    void callLlm_withChatRole_emitsModelRoleChatOnSuccess() {
        ScriptedLlmClient llm =
                new ScriptedLlmClient(LlmRetryConfig.DISABLED)
                        .withRole(LlmModelRole.CHAT)
                        .thenSucceed(new LlmResponse("ok", Map.of()));

        Harness h = harness();
        StepVerifier.create(
                        LlmAgentSupport.callLlm(
                                h.ctx, "stage-1", "agent-1", llm, List.of(new LlmMessage("user", "hi"))))
                .assertNext(r -> assertThat(r.content()).isEqualTo("ok"))
                .verifyComplete();

        Map<String, String> attrs = h.eventAttrs("LLM_CALL_SUCCEEDED");
        assertThat(attrs).containsEntry("model_role", "chat");
        assertThat(attrs).doesNotContainKey("modelRole");

        List<LlmCallRecord> records = h.store.llmCallRecordsFor(h.runId);
        assertThat(records.get(0).metadata()).containsEntry("model_role", "chat");
    }

    @Test
    void callLlm_withChatRole_emitsModelRoleChatOnFailure() {
        ScriptedLlmClient llm =
                new ScriptedLlmClient(LlmRetryConfig.DISABLED)
                        .withRole(LlmModelRole.CHAT)
                        .thenFail(http400());

        Harness h = harness();
        StepVerifier.create(
                        LlmAgentSupport.callLlm(
                                h.ctx, "stage-1", "agent-1", llm, List.of(new LlmMessage("user", "hi"))))
                .expectErrorSatisfies(err -> assertThat(err).isInstanceOf(LlmCallException.class))
                .verify();

        Map<String, String> attrs = h.eventAttrs("LLM_CALL_FAILED");
        assertThat(attrs).containsEntry("model_role", "chat");
        assertThat(attrs).doesNotContainKey("modelRole");

        List<LlmCallRecord> records = h.store.llmCallRecordsFor(h.runId);
        assertThat(records.get(0).metadata()).containsEntry("model_role", "chat");
    }

    private static LlmCallException http503() {
        return LlmCallException.httpFailure(
                503, "service unavailable", "http://test/v1/chat/completions", "test-model", "{}", null);
    }

    private static LlmCallException http400() {
        return LlmCallException.httpFailure(
                400, "bad request", "http://test/v1/chat/completions", "test-model", "{}", null);
    }

    private static LlmCallException timeout() {
        return LlmCallException.transport(
                "http://test/v1/chat/completions",
                "test-model",
                "{}",
                new TimeoutException("Did not observe any item or terminal signal within 5000ms"));
    }

    private Harness harness() {
        RunStore store = new InMemoryRunStore();
        RunEventPublisher publisher = new RunEventPublisher();
        UUID runId = UUID.randomUUID();
        WorkflowRunContext ctx =
                new WorkflowRunContext(
                        runId, "test-wf", "corr", "input", store, publisher, new AtomicBoolean(false));
        return new Harness(store, publisher, ctx, runId);
    }

    private record Harness(RunStore store, RunEventPublisher publisher, WorkflowRunContext ctx, UUID runId) {
        List<String> eventTypes() {
            return store.eventsFor(runId).stream().map(RuntimeEvent::type).toList();
        }

        List<String> warnings() {
            return store.eventsFor(runId).stream()
                    .filter(e -> "WARNING_RECORDED".equals(e.type()))
                    .map(e -> e.attributes().getOrDefault("message", ""))
                    .toList();
        }

        Map<String, String> eventAttrs(String type) {
            return store.eventsFor(runId).stream()
                    .filter(e -> type.equals(e.type()))
                    .map(RuntimeEvent::attributes)
                    .findFirst()
                    .orElse(Map.of());
        }
    }

    /** Per-invocation scripted LlmClient used to deterministically replay successes/failures. */
    private static final class ScriptedLlmClient implements LlmClient {
        private final Deque<Mono<LlmResponse>> script = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final LlmRetryConfig retry;
        private LlmModelRole role;

        ScriptedLlmClient(LlmRetryConfig retry) {
            this.retry = retry;
        }

        ScriptedLlmClient withRole(LlmModelRole role) {
            this.role = role;
            return this;
        }

        @Override
        public LlmModelRole modelRole() {
            return role;
        }

        ScriptedLlmClient thenSucceed(LlmResponse response) {
            script.add(Mono.just(response));
            return this;
        }

        ScriptedLlmClient thenFail(Throwable error) {
            script.add(Mono.error(error));
            return this;
        }

        int invocations() {
            return calls.get();
        }

        @Override
        public Mono<LlmResponse> chatCompletion(LlmRequest request) {
            // Defer so each retry resubscribes and pops a fresh scripted outcome — mirrors the
            // real WebClient-based client where every subscribe issues a new HTTP request.
            return Mono.defer(
                    () -> {
                        calls.incrementAndGet();
                        Mono<LlmResponse> next = script.poll();
                        if (next == null) {
                            return Mono.error(new IllegalStateException("ScriptedLlmClient exhausted"));
                        }
                        return next;
                    });
        }

        @Override
        public String defaultModel() {
            return "test-model";
        }

        @Override
        public String chatCompletionsEndpoint() {
            return "http://test/v1/chat/completions";
        }

        @Override
        public Mono<Boolean> health() {
            return Mono.just(true);
        }

        @Override
        public Mono<List<String>> listModels() {
            return Mono.just(List.of("test-model"));
        }

        @Override
        public LlmRetryConfig retryConfig() {
            return retry;
        }
    }
}
