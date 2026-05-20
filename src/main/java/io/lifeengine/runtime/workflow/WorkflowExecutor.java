package io.lifeengine.runtime.workflow;

import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentRegistry;
import io.lifeengine.runtime.agents.ClassifierAgent;
import io.lifeengine.runtime.agents.SummarizerAgent;
import io.lifeengine.runtime.core.InMemoryRunStore;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.events.RunEventPublisher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Observable LLM workflow: SummarizerAgent → ClassifierAgent with WORKFLOW_* and LLM_* events.
 */
@Component
public class WorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutor.class);

    public static final WorkflowDefinition LLM_DEMO =
            new WorkflowDefinition(
                    WorkflowRunContext.LLM_WORKFLOW_ID,
                    List.of(
                            new WorkflowStage(SummarizerAgent.AGENT_ID, 1),
                            new WorkflowStage(ClassifierAgent.AGENT_ID, 2)));

    private final InMemoryRunStore store;
    private final RunEventPublisher eventPublisher;
    private final AgentRegistry agentRegistry;
    private final ConcurrentHashMap<UUID, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Disposable> activeJobs = new ConcurrentHashMap<>();

    public WorkflowExecutor(
            InMemoryRunStore store,
            RunEventPublisher eventPublisher,
            AgentRegistry agentRegistry) {
        this.store = store;
        this.eventPublisher = eventPublisher;
        this.agentRegistry = agentRegistry;
    }

    public void schedule(UUID runId, String input) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancelFlags.put(runId, cancelled);
        WorkflowRunContext ctx =
                new WorkflowRunContext(
                        runId,
                        WorkflowRunContext.LLM_WORKFLOW_ID,
                        input,
                        store,
                        eventPublisher,
                        cancelled);

        ctx.emit("WORKFLOW_STARTED", Map.of("workflowId", ctx.workflowId()), false);

        Disposable disposable =
                execute(ctx)
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                                null,
                                err -> failRun(ctx, err),
                                () -> {
                                    activeJobs.remove(runId);
                                    cancelFlags.remove(runId);
                                });
        activeJobs.put(runId, disposable);
    }

    public boolean requestCancel(UUID runId) {
        AtomicBoolean flag = cancelFlags.get(runId);
        if (flag != null) {
            flag.set(true);
        }
        Disposable job = activeJobs.remove(runId);
        if (job != null && !job.isDisposed()) {
            job.dispose();
            return true;
        }
        return flag != null;
    }

    private Mono<Void> execute(WorkflowRunContext ctx) {
        return Mono.defer(
                        () -> {
                            if (ctx.isCancelled()) {
                                return Mono.error(new IllegalStateException("Run cancelled"));
                            }
                            return Mono.empty();
                        })
                .then(runAgent(ctx, SummarizerAgent.AGENT_ID, ctx.input(), Map.of()))
                .flatMap(
                        summary ->
                                runAgent(
                                        ctx,
                                        ClassifierAgent.AGENT_ID,
                                        summary,
                                        Map.of("summary", WorkflowRunContext.truncate(summary, 500))))
                .then(
                        Mono.fromRunnable(
                                () ->
                                        ctx.emit(
                                                "WORKFLOW_COMPLETED",
                                                Map.of("workflowId", ctx.workflowId()),
                                                false)))
                .then(
                        Mono.fromRunnable(
                                () -> ctx.emit("RUN_COMPLETED", Map.of(), true)))
                .then(completeRun(ctx));
    }

    private Mono<String> runAgent(
            WorkflowRunContext ctx, String agentId, String input, Map<String, String> context) {
        log.info("Executing agent {} for runId={}", agentId, ctx.runId());
        return agentRegistry
                .require(agentId)
                .execute(new AgentExecutionRequest(ctx.runId(), agentId, input, context), ctx)
                .flatMap(
                        result -> {
                            if (!result.success()) {
                                return Mono.error(
                                        new IllegalStateException(
                                                result.error() != null
                                                        ? result.error()
                                                        : "Agent failed: " + agentId));
                            }
                            return Mono.just(
                                    result.classification() != null
                                            ? result.classification()
                                            : result.output());
                        });
    }

    private Mono<Void> completeRun(WorkflowRunContext ctx) {
        return Mono.fromRunnable(
                () -> {
                    if (ctx.isCancelled()) {
                        return;
                    }
                    Instant now = Instant.now();
                    store.findRun(ctx.runId())
                            .ifPresent(
                                    run -> {
                                        Run updated = run.withStatus(RunStatus.SUCCEEDED, now);
                                        store.saveRun(updated);
                                    });
                    log.debug("LLM workflow completed runId={}", ctx.runId());
                });
    }

    private void failRun(WorkflowRunContext ctx, Throwable err) {
        if (ctx.isCancelled()) {
            return;
        }
        String message = err.getMessage() == null ? err.toString() : err.getMessage();
        ctx.emit("RUN_FAILED", Map.of("error", WorkflowRunContext.truncate(message, 500)), true);
        Instant now = Instant.now();
        store.findRun(ctx.runId())
                .ifPresent(
                        run -> {
                            Run updated = run.withStatus(RunStatus.FAILED, now);
                            store.saveRun(updated);
                        });
        cancelFlags.remove(ctx.runId());
        log.error(
                "LLM workflow failed runId={} workflowId={} error={}",
                ctx.runId(),
                ctx.workflowId(),
                message,
                err);
    }
}
