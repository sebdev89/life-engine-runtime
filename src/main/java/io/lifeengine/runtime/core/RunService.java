package io.lifeengine.runtime.core;

import io.lifeengine.runtime.api.RunDetailResponse;
import io.lifeengine.runtime.api.StartRunRequest;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.events.RunEventPublisher;
import io.lifeengine.runtime.observability.RunLogContext;
import io.lifeengine.runtime.observability.RuntimeMetrics;
import io.lifeengine.runtime.workflow.WorkflowRouter;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final RunStore store;
    private final WorkflowRouter workflowRouter;
    private final RunEventPublisher eventPublisher;
    private final RuntimeMetrics metrics;

    public RunService(
            RunStore store,
            WorkflowRouter workflowRouter,
            RunEventPublisher eventPublisher,
            RuntimeMetrics metrics) {
        this.store = store;
        this.workflowRouter = workflowRouter;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
    }

    public Mono<Run> startRun(StartRunRequest request) {
        Timer.Sample sample = metrics.startRunTimer();
        // Phase-1 JWT pass-through: capture the inbound caller's Authentication HERE, while
        // we are still inside the controller's request Reactor Context (populated by
        // RuntimeJwtAuthenticationWebFilter). Once we descend into Mono.fromCallable below
        // and from there imperatively into WorkflowRouter / DefinitionDrivenWorkflowExecutor
        // — which fire-and-forget .subscribe() the workflow Mono — there is no longer any
        // upstream Context to read from. Pass the Authentication forward so the executor can
        // re-attach it via contextWrite(ReactiveSecurityContextHolder.withAuthentication(...))
        // before subscribing the workflow chain. Without this hop, outbound WebClient filters
        // (e.g. cryptobotWebClient's jwtPropagationFilter) see Context.empty() and forward
        // requests with no Authorization header, producing 401s.
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Optional::ofNullable)
                .defaultIfEmpty(Optional.empty())
                .flatMap(maybeAuth -> startRunInternal(request, maybeAuth.orElse(null), sample));
    }

    private Mono<Run> startRunInternal(StartRunRequest request, Authentication caller, Timer.Sample sample) {
        return Mono.fromCallable(
                        () -> {
                            Instant now = Instant.now();
                            UUID runId = UUID.randomUUID();
                            String workflowId = request.workflowId().trim();
                            String correlationId =
                                    request.correlationId() != null && !request.correlationId().isBlank()
                                            ? request.correlationId().trim()
                                            : "corr-" + runId;
                            String input = request.input().trim();
                            Map<String, Object> metadata = new HashMap<>(request.metadata());
                            metadata.put("input", input);

                            Run run =
                                    new Run(
                                            runId,
                                            RunStatus.QUEUED,
                                            workflowId,
                                            correlationId,
                                            now,
                                            now,
                                            null,
                                            null,
                                            metadata);
                            store.saveRun(run);
                            Run running = run.withStatus(RunStatus.RUNNING, Instant.now()).withStartedAt(now);
                            store.saveRun(running);

                            String executor =
                                    workflowRouter.start(workflowId, runId, input, correlationId, caller);
                            metadata.put("executor", executor);
                            store.saveRun(
                                    new Run(
                                            running.id(),
                                            running.status(),
                                            running.workflowId(),
                                            running.correlationId(),
                                            running.createdAt(),
                                            running.updatedAt(),
                                            running.startedAt(),
                                            running.finishedAt(),
                                            metadata));

                            RunLogContext.put(correlationId, runId.toString(), workflowId);
                            try {
                                metrics.recordRunStarted(workflowId);
                                log.info(
                                        "Run started runId={} workflowId={} correlationId={} executor={}",
                                        runId,
                                        workflowId,
                                        correlationId,
                                        executor);
                                return store.findRun(runId).orElse(running);
                            } finally {
                                RunLogContext.clearRun();
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(
                        run ->
                                metrics.stopRunTimer(
                                        sample, run.workflowId(), run.status().name()));
    }

    public Mono<RunDetailResponse> getRunDetail(UUID runId) {
        return Mono.fromCallable(
                        () -> {
                            Run run =
                                    store.findRun(runId)
                                            .orElseThrow(() -> new RunNotFoundException(runId));
                            return new RunDetailResponse(
                                    run,
                                    store.agentStagesFor(runId),
                                    store.llmCallRecordsFor(runId),
                                    store.eventsFor(runId));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Run> getRun(UUID runId) {
        return getRunDetail(runId).map(RunDetailResponse::run);
    }

    public Mono<Run> cancelRun(UUID runId) {
        return Mono.fromCallable(
                        () -> {
                            Run run =
                                    store.findRun(runId)
                                            .orElseThrow(() -> new RunNotFoundException(runId));
                            if (run.status().isTerminal()) {
                                throw new IllegalStateException(
                                        "Run already terminal: " + run.status());
                            }
                            boolean signalled = workflowRouter.requestCancel(run.workflowId(), runId);
                            Instant now = Instant.now();
                            Run cancelled = run.withStatus(RunStatus.CANCELLED, now);
                            Map<String, Object> metadata = new HashMap<>(cancelled.metadata());
                            metadata.put(
                                    "cancelNote",
                                    signalled
                                            ? "Cancellation signalled; in-flight LLM HTTP calls may still complete."
                                            : "Run marked cancelled; no active workflow job found.");
                            Run withNote =
                                    new Run(
                                            cancelled.id(),
                                            cancelled.status(),
                                            cancelled.workflowId(),
                                            cancelled.correlationId(),
                                            cancelled.createdAt(),
                                            cancelled.updatedAt(),
                                            cancelled.startedAt(),
                                            cancelled.finishedAt(),
                                            metadata);
                            store.saveRun(withNote);
                            RuntimeEvent event =
                                    RuntimeEvent.of(
                                            runId,
                                            EventType.RUN_CANCELLED.wireName(),
                                            Map.of(
                                                    "reason",
                                                    "operator_cancel",
                                                    "workflowId",
                                                    run.workflowId(),
                                                    "correlationId",
                                                    run.correlationId()),
                                            true);
                            store.appendEvent(event);
                            eventPublisher.publish(event);
                            RunLogContext.put(
                                    run.correlationId(), runId.toString(), run.workflowId());
                            try {
                                metrics.recordRunTerminal(run.workflowId(), RunStatus.CANCELLED.name());
                                log.info(
                                        "Run cancelled runId={} workflowId={} correlationId={}",
                                        runId,
                                        run.workflowId(),
                                        run.correlationId());
                                return withNote;
                            } finally {
                                RunLogContext.clearRun();
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

}
