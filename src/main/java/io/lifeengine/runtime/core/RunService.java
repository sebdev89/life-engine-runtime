package io.lifeengine.runtime.core;

import io.lifeengine.runtime.api.StartRunRequest;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.events.RunEventPublisher;
import io.lifeengine.runtime.workflow.WorkflowRouter;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final InMemoryRunStore store;
    private final WorkflowRouter workflowRouter;
    private final RunEventPublisher eventPublisher;

    public RunService(
            InMemoryRunStore store,
            WorkflowRouter workflowRouter,
            RunEventPublisher eventPublisher) {
        this.store = store;
        this.workflowRouter = workflowRouter;
        this.eventPublisher = eventPublisher;
    }

    public Mono<Run> startRun(StartRunRequest request) {
        return Mono.fromCallable(
                        () -> {
                            Instant now = Instant.now();
                            UUID runId = UUID.randomUUID();
                            String workflowId = resolveWorkflowId(request.workflowId());
                            String correlationId =
                                    request.correlationId() != null && !request.correlationId().isBlank()
                                            ? request.correlationId().trim()
                                            : "corr-" + runId;
                            String input = resolveInput(request.input());
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

                            String executor = workflowRouter.start(workflowId, runId, input);
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

                            log.info(
                                    "Run started runId={} workflowId={} executor={}",
                                    runId,
                                    workflowId,
                                    executor);
                            return store.findRun(runId).orElse(running);
                        });
    }

    public Mono<Run> getRun(UUID runId) {
        return Mono.fromCallable(
                        () ->
                                store.findRun(runId)
                                        .orElseThrow(() -> new RunNotFoundException(runId)));
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
                            workflowRouter.requestCancel(run.workflowId(), runId);
                            Instant now = Instant.now();
                            Run cancelled = run.withStatus(RunStatus.CANCELLED, now);
                            store.saveRun(cancelled);
                            RuntimeEvent event =
                                    RuntimeEvent.of(
                                            runId,
                                            "RUN_CANCELLED",
                                            Map.of("reason", "operator_cancel"),
                                            true);
                            store.appendEvent(event);
                            eventPublisher.publish(event);
                            return cancelled;
                        });
    }

    static String resolveWorkflowId(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return WorkflowRunContext.LLM_WORKFLOW_ID;
        }
        return workflowId.trim();
    }

    private static String resolveInput(String input) {
        if (input == null || input.isBlank()) {
            return WorkflowRunContext.DEFAULT_INPUT;
        }
        return input.trim();
    }
}
