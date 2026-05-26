package io.lifeengine.runtime.workflow;

import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentRegistry;
import io.lifeengine.runtime.core.RunStore;
import io.lifeengine.runtime.domain.AgentStageRecord;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.tools.ToolExecutionRequest;
import io.lifeengine.runtime.tools.ToolNotFoundException;
import io.lifeengine.runtime.observability.RunLogContext;
import io.lifeengine.runtime.observability.RuntimeMetrics;
import io.lifeengine.runtime.observability.RuntimeObservation;
import io.lifeengine.runtime.tools.ToolRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 * Generic workflow executor: runs {@link WorkflowDefinition} stages in order, emits lifecycle events,
 * records agent/LLM data — fails loudly on unknown agent/tool (no fallback).
 */
@Component
public class DefinitionDrivenWorkflowExecutor implements WorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefinitionDrivenWorkflowExecutor.class);

    private final RunStore store;
    private final io.lifeengine.runtime.events.RunEventPublisher eventPublisher;
    private final AgentRegistry agentRegistry;
    private final ToolRegistry toolRegistry;
    private final RuntimeMetrics metrics;
    private final RuntimeObservation observation;
    private final ConcurrentHashMap<UUID, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Disposable> activeJobs = new ConcurrentHashMap<>();

    public DefinitionDrivenWorkflowExecutor(
            RunStore store,
            io.lifeengine.runtime.events.RunEventPublisher eventPublisher,
            AgentRegistry agentRegistry,
            ToolRegistry toolRegistry,
            RuntimeMetrics metrics,
            RuntimeObservation observation) {
        this.store = store;
        this.eventPublisher = eventPublisher;
        this.agentRegistry = agentRegistry;
        this.toolRegistry = toolRegistry;
        this.metrics = metrics;
        this.observation = observation;
    }

    @Override
    public void schedule(UUID runId, WorkflowDefinition definition, String input, String correlationId) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancelFlags.put(runId, cancelled);
        WorkflowRunContext ctx =
                new WorkflowRunContext(
                        runId, definition.workflowId(), correlationId, input, store, eventPublisher, cancelled);

        ctx.emit(EventType.RUN_STARTED, Map.of(), false);

        RunLogContext.put(correlationId, runId.toString(), definition.workflowId());
        Disposable disposable =
                observation
                        .observeRun(
                                definition.workflowId(),
                                runId.toString(),
                                correlationId,
                                executeDefinition(ctx, definition))
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

    @Override
    public boolean requestCancel(UUID runId) {
        AtomicBoolean flag = cancelFlags.get(runId);
        if (flag != null) {
            flag.set(true);
        }
        Disposable job = activeJobs.remove(runId);
        if (job != null && !job.isDisposed()) {
            job.dispose();
        }
        return flag != null;
    }

    private Mono<Void> executeDefinition(WorkflowRunContext ctx, WorkflowDefinition definition) {
        List<WorkflowStage> stages =
                definition.stages().stream().sorted(Comparator.comparingInt(WorkflowStage::order)).toList();
        Duration stageTimeout = definition.stageTimeout();

        Mono<String> chain = Mono.just(ctx.input());
        for (WorkflowStage stage : stages) {
            chain =
                    chain.flatMap(
                            previous ->
                                    executeStage(ctx, stage, previous, stageTimeout)
                                            .doOnNext(output -> ctx.putStageOutput(stage.stageId(), output)));
        }

        return chain.then(
                        Mono.defer(
                                () -> {
                                    if (ctx.isCancelled()) {
                                        return Mono.empty();
                                    }
                                    ctx.emit(EventType.RUN_SUCCEEDED, Map.of(), true);
                                    return Mono.empty();
                                }))
                .then(completeRun(ctx));
    }

    private Mono<String> executeStage(
            WorkflowRunContext ctx, WorkflowStage stage, String input, Duration stageTimeout) {
        return Mono.defer(
                () -> {
                    if (ctx.isCancelled()) {
                        return Mono.error(new IllegalStateException("Run cancelled"));
                    }
                    Map<String, String> stageAttrs = WorkflowRunContext.stageAttrs(stage);
                    Instant stageStarted = Instant.now();
                    ctx.emit(EventType.STAGE_STARTED, stageAttrs, false);

                    Mono<String> execution =
                            switch (stage.kind()) {
                                case AGENT -> runAgentStage(ctx, stage, input, stageStarted);
                                case TOOL -> runToolStage(ctx, stage, input, stageStarted);
                            };

                    Mono<String> observed =
                            observation.observeStage(
                                    ctx.workflowId(),
                                    ctx.runId().toString(),
                                    stage.stageId(),
                                    stage.kind().name(),
                                    execution);

                    Mono<String> bounded = applyStageTimeout(observed, stageTimeout);

                    return bounded
                            .doOnSuccess(
                                    output -> {
                                        ctx.emit(EventType.STAGE_SUCCEEDED, stageAttrs, false);
                                        metrics.recordStage(stage.kind().name(), "SUCCEEDED");
                                        if (stage.kind() == WorkflowStage.StageKind.AGENT) {
                                            metrics.recordAgent(stage.refId(), "SUCCEEDED");
                                        } else {
                                            metrics.recordTool(stage.refId(), "SUCCEEDED");
                                        }
                                        recordStageSuccess(ctx, stage, input, output, stageStarted);
                                    })
                            .onErrorResume(
                                    error -> {
                                        String errMsg = WorkflowRunContext.truncate(error.getMessage(), 500);
                                        ctx.emit(
                                                EventType.STAGE_FAILED,
                                                merge(stageAttrs, Map.of("error", errMsg)),
                                                false);
                                        metrics.recordStage(stage.kind().name(), "FAILED");
                                        if (stage.kind() == WorkflowStage.StageKind.AGENT) {
                                            metrics.recordAgent(stage.refId(), "FAILED");
                                        } else {
                                            metrics.recordTool(stage.refId(), "FAILED");
                                        }
                                        recordStageFailure(ctx, stage, input, errMsg, stageStarted);
                                        return Mono.error(error);
                                    });
                });
    }

    private static Mono<String> applyStageTimeout(Mono<String> stage, Duration stageTimeout) {
        if (stageTimeout == null || stageTimeout.isZero() || stageTimeout.isNegative()) {
            return stage;
        }
        return stage.timeout(
                stageTimeout,
                Mono.error(
                        () ->
                                new IllegalStateException(
                                        "stage timeout exceeded after " + stageTimeout)));
    }

    private Mono<String> runAgentStage(
            WorkflowRunContext ctx, WorkflowStage stage, String input, Instant stageStarted) {
        String agentId = stage.refId();
        log.info(
                "Executing agent {} stage={} runId={} workflowId={} correlationId={}",
                agentId,
                stage.stageId(),
                ctx.runId(),
                ctx.workflowId(),
                ctx.correlationId());

        return agentRegistry
                .require(agentId)
                .execute(
                        new AgentExecutionRequest(
                                ctx.runId(), agentId, stage.stageId(), input, ctx.agentOutputs()),
                        ctx)
                .flatMap(
                        result -> {
                            if (!result.success()) {
                                String error =
                                        result.error() != null ? result.error() : "Agent failed: " + agentId;
                                ctx.emit(
                                        EventType.AGENT_FAILED,
                                        Map.of(
                                                "agentId",
                                                agentId,
                                                "stageId",
                                                stage.stageId(),
                                                "error",
                                                WorkflowRunContext.truncate(error, 500)),
                                        false);
                                return Mono.error(new IllegalStateException(error));
                            }
                            String output =
                                    result.classification() != null
                                            ? result.classification()
                                            : result.output();
                            ctx.putAgentOutput(agentId, output);
                            return Mono.just(output);
                        });
    }

    private Mono<String> runToolStage(
            WorkflowRunContext ctx, WorkflowStage stage, String input, Instant stageStarted) {
        String toolId = stage.refId();
        return toolRegistry
                .require(toolId)
                .execute(new ToolExecutionRequest(ctx.runId(), toolId, input, Map.of()), ctx)
                .flatMap(
                        result -> {
                            if (!result.success()) {
                                String error =
                                        result.error() != null
                                                ? result.error()
                                                : "Tool failed: " + toolId;
                                ctx.emit(
                                        EventType.TOOL_FAILED,
                                        Map.of(
                                                "toolId",
                                                toolId,
                                                "stageId",
                                                stage.stageId(),
                                                "error",
                                                WorkflowRunContext.truncate(error, 500)),
                                        false);
                                return Mono.error(new IllegalStateException(error));
                            }
                            return Mono.just(result.output());
                        })
                .onErrorMap(ToolNotFoundException.class, ex -> ex);
    }

    private static void recordStageSuccess(
            WorkflowRunContext ctx, WorkflowStage stage, String input, String output, Instant started) {
        Instant finished = Instant.now();
        if (stage.kind() == WorkflowStage.StageKind.AGENT) {
            ctx.recordStage(
                    AgentStageRecord.agent(
                            stage.stageId(),
                            stage.refId(),
                            "SUCCEEDED",
                            started,
                            finished,
                            WorkflowRunContext.truncate(input, 500),
                            WorkflowRunContext.truncate(output, 2000),
                            null,
                            WorkflowRunContext.stageAttrs(stage)));
        } else {
            ctx.recordStage(
                    AgentStageRecord.tool(
                            stage.stageId(),
                            stage.refId(),
                            "SUCCEEDED",
                            started,
                            finished,
                            WorkflowRunContext.truncate(input, 500),
                            WorkflowRunContext.truncate(output, 2000),
                            null,
                            WorkflowRunContext.stageAttrs(stage)));
        }
    }

    private static void recordStageFailure(
            WorkflowRunContext ctx,
            WorkflowStage stage,
            String input,
            String error,
            Instant started) {
        Instant finished = Instant.now();
        if (stage.kind() == WorkflowStage.StageKind.AGENT) {
            ctx.recordStage(
                    AgentStageRecord.agent(
                            stage.stageId(),
                            stage.refId(),
                            "FAILED",
                            started,
                            finished,
                            WorkflowRunContext.truncate(input, 500),
                            null,
                            error,
                            WorkflowRunContext.stageAttrs(stage)));
        } else {
            ctx.recordStage(
                    AgentStageRecord.tool(
                            stage.stageId(),
                            stage.refId(),
                            "FAILED",
                            started,
                            finished,
                            WorkflowRunContext.truncate(input, 500),
                            null,
                            error,
                            WorkflowRunContext.stageAttrs(stage)));
        }
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
                    metrics.recordRunTerminal(ctx.workflowId(), RunStatus.SUCCEEDED.name());
                    log.info(
                            "Workflow completed runId={} workflowId={} correlationId={}",
                            ctx.runId(),
                            ctx.workflowId(),
                            ctx.correlationId());
                });
    }

    private void failRun(WorkflowRunContext ctx, Throwable err) {
        if (ctx.isCancelled()) {
            return;
        }
        String message = err.getMessage() == null ? err.toString() : err.getMessage();
        ctx.emit(EventType.RUN_FAILED, Map.of("error", WorkflowRunContext.truncate(message, 500)), true);
        Instant now = Instant.now();
        store.findRun(ctx.runId())
                .ifPresent(
                        run -> {
                            Run updated = run.withStatus(RunStatus.FAILED, now);
                            store.saveRun(updated);
                        });
        cancelFlags.remove(ctx.runId());
        metrics.recordRunTerminal(ctx.workflowId(), RunStatus.FAILED.name());
        log.error(
                "Workflow failed runId={} workflowId={} correlationId={} error={}",
                ctx.runId(),
                ctx.workflowId(),
                ctx.correlationId(),
                message,
                err);
    }

    private static Map<String, String> merge(Map<String, String> base, Map<String, String> extra) {
        Map<String, String> merged = new LinkedHashMap<>(base);
        merged.putAll(extra);
        return merged;
    }
}
