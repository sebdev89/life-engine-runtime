package io.lifeengine.runtime.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.agents.AgentRegistry;
import io.lifeengine.runtime.agents.LlmAgentSupport;
import io.lifeengine.runtime.core.InMemoryRunStore;
import io.lifeengine.runtime.core.RunStore;
import io.lifeengine.runtime.domain.AgentStageRecord;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.events.RunEventPublisher;
import io.lifeengine.runtime.llm.LlmCallException;
import io.lifeengine.runtime.llm.LlmCallRecord;
import io.lifeengine.runtime.llm.LlmClient;
import io.lifeengine.runtime.llm.LlmMessage;
import io.lifeengine.runtime.llm.LlmRequest;
import io.lifeengine.runtime.llm.LlmResponse;
import io.lifeengine.runtime.observability.RuntimeMetrics;
import io.lifeengine.runtime.observability.RuntimeObservation;
import io.lifeengine.runtime.tools.ToolRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Regression test for the R2DBC blocking-on-event-loop bug.
 *
 * <p>Before the fix, when a {@code WebClient}-driven LLM call failed, the subsequent
 * {@code ctx.emit(...)} / {@code store.appendEvent(...)} chain (inside
 * {@code LlmAgentSupport.callLlm}, agent {@code .flatMap/.onErrorResume}, the executor's
 * stage {@code doOnSuccess/onErrorResume}, and finally the {@code failRun} callback)
 * executed on the same Netty event-loop thread that delivered the response. With
 * {@code R2dbcRunStore}, that triggered {@code block()} on a {@link reactor.core.scheduler.NonBlocking}
 * thread and tripped Reactor's blocking detector.
 *
 * <p>Since this suite cannot easily spin up Netty, the simulation uses a scripted
 * {@link LlmClient} whose error Mono is published on {@code Schedulers.parallel()} — which
 * also implements {@code NonBlocking} (identical contract to {@code reactor-http-epoll-*}
 * from {@link reactor.core.scheduler.Schedulers#isInNonBlockingThread()}'s point of view).
 *
 * <p>The {@link ThreadAuditingRunStore} decorator records the calling thread name and
 * non-blocking flag for every read/write operation. The test asserts no
 * {@code RunStore} call landed on a non-blocking thread, and that the run still completed
 * with the expected {@code RUN_FAILED} terminal event.
 */
class EventLoopBlockingSafetyTest {

    private static final String WORKFLOW_ID = "test.event-loop.blocking";
    private static final String AGENT_ID = "test-llm-failing-agent";

    @Test
    void llmFailureFromNonBlockingThread_neverInvokesRunStoreOnNonBlockingThread()
            throws InterruptedException {
        ThreadAuditingRunStore store = new ThreadAuditingRunStore(new InMemoryRunStore());
        RunEventPublisher eventPublisher = new RunEventPublisher();
        NonBlockingErrorLlmClient llm = new NonBlockingErrorLlmClient();
        AgentRegistry agentRegistry = new AgentRegistry(List.of(new FailingLlmAgent(llm)));
        ToolRegistry toolRegistry = new ToolRegistry(List.of());
        RuntimeMetrics metrics = new RuntimeMetrics(new SimpleMeterRegistry());
        RuntimeObservation observation = new RuntimeObservation(Tracer.NOOP);

        DefinitionDrivenWorkflowExecutor executor =
                new DefinitionDrivenWorkflowExecutor(
                        store, eventPublisher, agentRegistry, toolRegistry, metrics, observation);

        WorkflowDefinition definition =
                new WorkflowDefinition(
                        WORKFLOW_ID,
                        "runtime.text.v1",
                        "runtime.text.v1",
                        List.of(WorkflowStage.agent(AGENT_ID, 1)),
                        null,
                        "Failing-LLM workflow that emits its error on a NonBlocking scheduler");

        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        store.saveRun(
                new Run(
                        runId,
                        RunStatus.RUNNING,
                        WORKFLOW_ID,
                        "corr-event-loop-safety",
                        now,
                        now,
                        now,
                        null,
                        Map.of()));

        executor.schedule(runId, definition, "input-1", "corr-event-loop-safety", null);

        awaitTerminal(store, runId, RunStatus.FAILED, Duration.ofSeconds(5));

        List<ThreadAuditingRunStore.Call> violations = store.nonBlockingCalls();
        Assertions.assertThat(violations)
                .as(
                        "RunStore must never be invoked on a NonBlocking thread. Offending calls: %s",
                        violations)
                .isEmpty();

        assertThat(llm.invocations()).isGreaterThanOrEqualTo(1);

        List<RuntimeEvent> events = store.eventsFor(runId);
        List<String> types = events.stream().map(RuntimeEvent::type).toList();
        Assertions.assertThat(types)
                .as("expected lifecycle including terminal RUN_FAILED")
                .containsSubsequence(
                        "RUN_STARTED",
                        "STAGE_STARTED",
                        "AGENT_STARTED",
                        "LLM_CALL_STARTED",
                        "LLM_CALL_FAILED",
                        "AGENT_FAILED",
                        "STAGE_FAILED",
                        "RUN_FAILED");

        RuntimeEvent runFailed =
                events.stream()
                        .filter(e -> "RUN_FAILED".equals(e.type()))
                        .findFirst()
                        .orElseThrow();
        assertThat(runFailed.terminal()).isTrue();
        assertThat(store.findRun(runId).orElseThrow().status()).isEqualTo(RunStatus.FAILED);
    }

    private static void awaitTerminal(
            RunStore store, UUID runId, RunStatus expected, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            RunStatus status =
                    store.findRun(runId).map(Run::status).orElse(RunStatus.RUNNING);
            if (status == expected) {
                return;
            }
            Thread.sleep(20);
        }
        Assertions.fail("Run did not reach " + expected + " within " + timeout);
    }

    /**
     * {@link RunStore} decorator that records the current thread for every call and flags
     * any invocation that happens on a {@link reactor.core.scheduler.NonBlocking} thread
     * (the same marker enforced by {@code Schedulers.isInNonBlockingThread()} and the
     * R2DBC blocking detector inside {@code R2dbcRunStore.block(...)}).
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

    /**
     * Minimal {@link LlmClient} whose error signal is delivered on {@code Schedulers.parallel()}
     * — a {@link reactor.core.scheduler.NonBlocking} scheduler identical (in Reactor's blocking
     * contract) to the {@code reactor-http-epoll-*} threads on which the real WebClient
     * publishes signals.
     */
    private static final class NonBlockingErrorLlmClient implements LlmClient {
        private final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();

        int invocations() {
            return calls.get();
        }

        @Override
        public Mono<LlmResponse> chatCompletion(LlmRequest request) {
            return Mono.<LlmResponse>defer(
                            () -> {
                                calls.incrementAndGet();
                                return Mono.error(
                                        LlmCallException.httpFailure(
                                                500,
                                                "{\"error\":\"simulated upstream failure\"}",
                                                chatCompletionsEndpoint(),
                                                defaultModel(),
                                                "{}",
                                                null));
                            })
                    .subscribeOn(Schedulers.parallel());
        }

        @Override
        public String defaultModel() {
            return "test-non-blocking-model";
        }

        @Override
        public String chatCompletionsEndpoint() {
            return "http://non-blocking-test/v1/chat/completions";
        }

        @Override
        public Mono<Boolean> health() {
            return Mono.just(true);
        }

        @Override
        public Mono<List<String>> listModels() {
            return Mono.just(List.of(defaultModel()));
        }
    }

    /**
     * Trivial agent that runs a single LLM call via {@link LlmAgentSupport#callLlm} so the
     * test exercises the exact code path documented in the bug report (LLM error → agent
     * {@code .onErrorResume} → executor {@code .onErrorResume} → {@code failRun}).
     */
    private static final class FailingLlmAgent implements AgentExecutor {
        private final LlmClient llmClient;

        FailingLlmAgent(LlmClient llmClient) {
            this.llmClient = llmClient;
        }

        @Override
        public String agentId() {
            return AGENT_ID;
        }

        @Override
        public Set<String> capabilities() {
            return Set.of("execute", "llm");
        }

        @Override
        public Mono<AgentExecutionResult> execute(
                AgentExecutionRequest request, WorkflowRunContext ctx) {
            ctx.emit(
                    io.lifeengine.runtime.domain.EventType.AGENT_STARTED,
                    Map.of("agentId", AGENT_ID),
                    false);
            return LlmAgentSupport.callLlm(
                            ctx,
                            request.stageId(),
                            AGENT_ID,
                            llmClient,
                            List.of(new LlmMessage("user", request.input())))
                    .map(response -> AgentExecutionResult.ok(AGENT_ID, response.content()))
                    .onErrorResume(
                            error -> {
                                String msg =
                                        error.getMessage() == null
                                                ? error.toString()
                                                : error.getMessage();
                                ctx.emit(
                                        io.lifeengine.runtime.domain.EventType.AGENT_FAILED,
                                        Map.of("agentId", AGENT_ID, "error", msg),
                                        false);
                                return Mono.error(error);
                            });
        }
    }
}
