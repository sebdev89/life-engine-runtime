package io.lifeengine.runtime.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.runtime.api.RuntimeEventResponse;
import io.lifeengine.runtime.core.InMemoryRunStore;
import io.lifeengine.runtime.core.RunNotFoundException;
import io.lifeengine.runtime.core.RunStore;
import io.lifeengine.runtime.domain.AgentStageRecord;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.llm.LlmCallRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * Regression test for the SSE replay path: {@link RunEventStreamService#stream(UUID)} must hop
 * every blocking {@link RunStore} read onto {@code Schedulers.boundedElastic()} even when the
 * subscriber chain originates from (or chains through) a {@code NonBlocking} thread — which is
 * the operational reality, since the SSE handshake completes on a Netty {@code reactor-http-*}
 * event-loop before the controller hands the {@link reactor.core.publisher.Flux} to the
 * framework.
 *
 * <p>This is the companion to
 * {@link io.lifeengine.runtime.workflow.EventLoopBlockingSafetyTest}, which proves the same
 * invariant for the workflow executor / agent / LLM failure path. Together they pin down the
 * blocking contract on every code path that reaches the R2DBC-backed store.
 *
 * <p>The test wraps the store in {@link ThreadAuditingRunStore} (same pattern as the executor
 * test) and forces the SSE subscription to run from {@code Schedulers.parallel()} (a
 * {@code NonBlocking} scheduler with the same Reactor contract as {@code reactor-http-epoll-*}).
 * It then asserts no {@code RunStore} method was ever invoked on a non-blocking thread.
 */
class SseStreamBlockingSafetyTest {

    private static final String WORKFLOW_ID = "test.sse.blocking";

    @Test
    void streamSubscribedFromNonBlockingScheduler_neverInvokesRunStoreOnNonBlockingThread() {
        ThreadAuditingRunStore store = new ThreadAuditingRunStore(new InMemoryRunStore());
        RunEventPublisher publisher = new RunEventPublisher();
        RunEventStreamService service = new RunEventStreamService(store, publisher);

        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        store.saveRun(
                new Run(
                        runId,
                        RunStatus.RUNNING,
                        WORKFLOW_ID,
                        "corr-sse-safety",
                        now,
                        now,
                        now,
                        null,
                        Map.of()));
        // Seed replay history: a non-terminal event followed by a terminal one so the SSE
        // Flux completes deterministically without waiting on the keepalive interval.
        RuntimeEvent started =
                RuntimeEvent.of(runId, EventType.RUN_STARTED.wireName(), Map.of(), false);
        RuntimeEvent succeeded =
                RuntimeEvent.of(runId, EventType.RUN_SUCCEEDED.wireName(), Map.of(), true);
        store.appendEvent(started);
        store.appendEvent(succeeded);

        // Reset call list so the audit only covers the SSE subscription path.
        store.clearCalls();

        // Subscribe from Schedulers.parallel(), a NonBlocking scheduler equivalent to the
        // reactor-http-epoll-* threads on which the controller assembles the SSE response.
        Flux<ServerSentEvent<RuntimeEventResponse>> sse =
                service.stream(runId).subscribeOn(Schedulers.parallel());

        StepVerifier.create(sse.map(evt -> evt.data().type()))
                .expectNext(EventType.RUN_STARTED.wireName())
                .expectNext(EventType.RUN_SUCCEEDED.wireName())
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        List<ThreadAuditingRunStore.Call> violations = store.nonBlockingCalls();
        assertThat(violations)
                .as(
                        "RunStore must never be invoked on a NonBlocking thread during SSE replay."
                                + " Offending calls: %s",
                        violations)
                .isEmpty();

        // Defense in depth: the audit must have observed RunStore activity (otherwise the test
        // is a no-op and we never exercised the path we claim to be guarding).
        assertThat(store.allCalls())
                .as("SSE replay must have hit RunStore at least once (findRun + eventsFor)")
                .anyMatch(call -> call.operation().equals("findRun"))
                .anyMatch(call -> call.operation().equals("eventsFor"));
    }

    @Test
    void unknownRunId_propagatesRunNotFoundFromBoundedElastic() {
        ThreadAuditingRunStore store = new ThreadAuditingRunStore(new InMemoryRunStore());
        RunEventPublisher publisher = new RunEventPublisher();
        RunEventStreamService service = new RunEventStreamService(store, publisher);
        UUID missing = UUID.randomUUID();

        // Subscribing from parallel() is still safe — the existence check itself must hop
        // to boundedElastic before touching the store.
        StepVerifier.create(service.stream(missing).subscribeOn(Schedulers.parallel()))
                .expectError(RunNotFoundException.class)
                .verify(Duration.ofSeconds(5));

        assertThat(store.nonBlockingCalls())
                .as("Even the not-found path must not touch RunStore on a NonBlocking thread")
                .isEmpty();
    }

    /**
     * {@link RunStore} decorator that records the calling thread for every operation and flags
     * any invocation that happens on a {@link reactor.core.scheduler.NonBlocking} thread (the
     * same marker enforced by {@link reactor.core.scheduler.Schedulers#isInNonBlockingThread()}
     * and the R2DBC blocking detector inside {@code R2dbcRunStore.block(...)}).
     */
    private static final class ThreadAuditingRunStore implements RunStore {
        private final RunStore delegate;
        private final CopyOnWriteArrayList<Call> calls = new CopyOnWriteArrayList<>();

        ThreadAuditingRunStore(RunStore delegate) {
            this.delegate = delegate;
        }

        private void record(String operation) {
            calls.add(
                    new Call(
                            operation,
                            Thread.currentThread().getName(),
                            Schedulers.isInNonBlockingThread()));
        }

        List<Call> nonBlockingCalls() {
            return calls.stream().filter(Call::nonBlocking).toList();
        }

        List<Call> allCalls() {
            return List.copyOf(calls);
        }

        void clearCalls() {
            calls.clear();
        }

        @Override
        public void saveRun(Run run) {
            record("saveRun");
            delegate.saveRun(run);
        }

        @Override
        public Optional<Run> findRun(UUID runId) {
            record("findRun");
            return delegate.findRun(runId);
        }

        @Override
        public void appendEvent(RuntimeEvent event) {
            record("appendEvent[" + event.type() + "]");
            delegate.appendEvent(event);
        }

        @Override
        public List<RuntimeEvent> eventsFor(UUID runId) {
            record("eventsFor");
            return delegate.eventsFor(runId);
        }

        @Override
        public void appendAgentStage(UUID runId, AgentStageRecord stage) {
            record("appendAgentStage");
            delegate.appendAgentStage(runId, stage);
        }

        @Override
        public List<AgentStageRecord> agentStagesFor(UUID runId) {
            record("agentStagesFor");
            return delegate.agentStagesFor(runId);
        }

        @Override
        public void appendLlmCallRecord(UUID runId, LlmCallRecord record) {
            record("appendLlmCallRecord");
            delegate.appendLlmCallRecord(runId, record);
        }

        @Override
        public List<LlmCallRecord> llmCallRecordsFor(UUID runId) {
            record("llmCallRecordsFor");
            return delegate.llmCallRecordsFor(runId);
        }

        record Call(String operation, String thread, boolean nonBlocking) {}
    }
}
