package io.lifeengine.runtime.workflow;

import io.lifeengine.runtime.agents.AgentRegistry;
import io.lifeengine.runtime.core.InMemoryRunStore;
import io.lifeengine.runtime.core.RunStore;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.events.RunEventPublisher;
import io.lifeengine.runtime.observability.RuntimeMetrics;
import io.lifeengine.runtime.observability.RuntimeObservation;
import io.lifeengine.runtime.tools.ToolDefinition;
import io.lifeengine.runtime.tools.ToolExecutionRequest;
import io.lifeengine.runtime.tools.ToolExecutionResult;
import io.lifeengine.runtime.tools.ToolExecutor;
import io.lifeengine.runtime.tools.ToolRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Verifies {@link DefinitionDrivenWorkflowExecutor} enforces {@link WorkflowDefinition#stageTimeout()}.
 *
 * <p>Constructs the executor with real in-memory collaborators (no Spring context) and runs a
 * workflow whose only stage is a deliberately slow tool. The stage delay is far longer than the
 * workflow's {@code stageTimeout}, so the run must:
 *
 * <ul>
 *   <li>Reach {@link RunStatus#FAILED}.
 *   <li>Emit a {@code STAGE_FAILED} event whose {@code error} contains "stage timeout exceeded".
 *   <li>Emit a terminal {@code RUN_FAILED} event whose {@code error} contains "stage timeout exceeded".
 * </ul>
 */
class StageTimeoutWebFluxTest {

    private static final String SLOW_TOOL_ID = "test.slow-tool";
    private static final String SLOW_WORKFLOW_ID = "test.slow.workflow";
    private static final Duration TOOL_DELAY = Duration.ofSeconds(2);
    private static final Duration STAGE_TIMEOUT = Duration.ofMillis(150);

    @Test
    void slowStage_exceedingStageTimeout_failsRunWithTimeoutMessage() throws InterruptedException {
        SlowTool slowTool = new SlowTool();
        InMemoryRunStore store = new InMemoryRunStore();
        RunEventPublisher eventPublisher = new RunEventPublisher();
        AgentRegistry agentRegistry = new AgentRegistry(List.of());
        ToolRegistry toolRegistry = new ToolRegistry(List.of(slowTool));
        RuntimeMetrics metrics = new RuntimeMetrics(new SimpleMeterRegistry());
        RuntimeObservation observation = new RuntimeObservation(Tracer.NOOP);

        DefinitionDrivenWorkflowExecutor executor =
                new DefinitionDrivenWorkflowExecutor(
                        store, eventPublisher, agentRegistry, toolRegistry, metrics, observation);

        WorkflowDefinition definition =
                new WorkflowDefinition(
                        SLOW_WORKFLOW_ID,
                        "runtime.text.v1",
                        "runtime.text.v1",
                        List.of(WorkflowStage.tool(SLOW_TOOL_ID, 1)),
                        STAGE_TIMEOUT,
                        "Tool stage that exceeds workflow stageTimeout (test fixture)");

        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        store.saveRun(
                new Run(
                        runId,
                        RunStatus.RUNNING,
                        SLOW_WORKFLOW_ID,
                        "stage-timeout-test",
                        now,
                        now,
                        now,
                        null,
                        Map.of()));

        executor.schedule(runId, definition, "slow-input", "stage-timeout-test", null);

        awaitTerminal(store, runId, RunStatus.FAILED, Duration.ofSeconds(5));

        Run finalRun = store.findRun(runId).orElseThrow();
        Assertions.assertThat(finalRun.status()).isEqualTo(RunStatus.FAILED);

        Assertions.assertThat(slowTool.invocations.get())
                .as("slow tool subscribed exactly once")
                .isEqualTo(1);

        List<RuntimeEvent> events = store.eventsFor(runId);
        List<String> types = events.stream().map(RuntimeEvent::type).toList();
        Assertions.assertThat(types)
                .containsSubsequence("RUN_STARTED", "STAGE_STARTED", "STAGE_FAILED", "RUN_FAILED");
        Assertions.assertThat(types)
                .as("the slow stage must not appear as succeeded")
                .doesNotContain("STAGE_SUCCEEDED");

        RuntimeEvent stageFailed =
                events.stream()
                        .filter(e -> "STAGE_FAILED".equals(e.type()))
                        .findFirst()
                        .orElseThrow();
        Assertions.assertThat(stageFailed.attributes().get("error"))
                .as("STAGE_FAILED error attribute")
                .contains("stage timeout exceeded");

        RuntimeEvent runFailed =
                events.stream()
                        .filter(e -> "RUN_FAILED".equals(e.type()))
                        .findFirst()
                        .orElseThrow();
        Assertions.assertThat(runFailed.terminal()).isTrue();
        Assertions.assertThat(runFailed.attributes().get("error"))
                .as("RUN_FAILED error attribute")
                .contains("stage timeout exceeded");
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

    /** Test-only tool that delays its result long past {@link #STAGE_TIMEOUT}. */
    private static final class SlowTool implements ToolExecutor {

        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public String toolId() {
            return SLOW_TOOL_ID;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(SLOW_TOOL_ID, "Test fixture: deliberately slow tool");
        }

        @Override
        public Mono<ToolExecutionResult> execute(ToolExecutionRequest request, WorkflowRunContext ctx) {
            return Mono.defer(
                    () -> {
                        invocations.incrementAndGet();
                        String input = request.input() == null ? "" : request.input();
                        return Mono.delay(TOOL_DELAY)
                                .map(ignored -> ToolExecutionResult.ok(SLOW_TOOL_ID, input));
                    });
        }
    }
}
