package io.lifeengine.runtime.workflow;

import io.lifeengine.runtime.core.RunStore;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.events.RunEventPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Explicit demo pipeline without LLM ({@link WorkflowIds#DEMO_NO_LLM}) — never used as fallback.
 */
@Component
public class FakeWorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(FakeWorkflowExecutor.class);

    private final RunStore store;
    private final RunEventPublisher eventPublisher;
    private final Duration stepDelay;
    private final ConcurrentHashMap<UUID, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Disposable> activeJobs = new ConcurrentHashMap<>();

    public FakeWorkflowExecutor(
            RunStore store,
            RunEventPublisher eventPublisher,
            @Value("${lifeengine.runtime.fake-workflow.step-delay-ms:50}") long stepDelayMs) {
        this.store = store;
        this.eventPublisher = eventPublisher;
        this.stepDelay = Duration.ofMillis(Math.max(1, stepDelayMs));
    }

    public void schedule(UUID runId, String correlationId) {
        log.info("Starting fake demo workflow for runId={} (agent-a / agent-b)", runId);
        cancelFlags.put(runId, new AtomicBoolean(false));
        Disposable disposable =
                Flux.concat(
                                step(runId, correlationId, EventType.RUN_STARTED, Map.of(), false),
                                step(
                                        runId,
                                        correlationId,
                                        EventType.AGENT_STARTED,
                                        Map.of("agentId", "agent-a"),
                                        false),
                                step(
                                        runId,
                                        correlationId,
                                        EventType.AGENT_SUCCEEDED,
                                        Map.of("agentId", "agent-a"),
                                        false),
                                step(
                                        runId,
                                        correlationId,
                                        EventType.AGENT_STARTED,
                                        Map.of("agentId", "agent-b"),
                                        false),
                                step(
                                        runId,
                                        correlationId,
                                        EventType.AGENT_SUCCEEDED,
                                        Map.of("agentId", "agent-b"),
                                        false),
                                step(runId, correlationId, EventType.RUN_SUCCEEDED, Map.of(), true))
                        .then(Mono.fromRunnable(() -> completeRun(runId)))
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                                null,
                                err -> failRun(runId, err),
                                () -> activeJobs.remove(runId));
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
        }
        return flag != null;
    }

    private Mono<RuntimeEvent> step(
            UUID runId,
            String correlationId,
            EventType type,
            Map<String, String> attributes,
            boolean terminal) {
        return Mono.defer(
                        () -> {
                            if (isCancelled(runId)) {
                                return Mono.empty();
                            }
                            Map<String, String> attrs = new LinkedHashMap<>();
                            attrs.put("workflowId", WorkflowIds.DEMO_NO_LLM);
                            attrs.put("correlationId", correlationId);
                            attrs.putAll(attributes);
                            RuntimeEvent event = RuntimeEvent.of(runId, type.wireName(), attrs, terminal);
                            store.appendEvent(event);
                            eventPublisher.publish(event);
                            return Mono.just(event);
                        })
                // delayElement() without an explicit scheduler routes through
                // Schedulers.parallel(), which is a NonBlocking scheduler. Subsequent
                // step()'s Mono.defer body would then run on parallel and the blocking
                // store.appendEvent inside it would trip Reactor's blocking detector.
                .delayElement(stepDelay, Schedulers.boundedElastic());
    }

    private void completeRun(UUID runId) {
        if (isCancelled(runId)) {
            return;
        }
        Instant now = Instant.now();
        store.findRun(runId)
                .ifPresent(
                        run -> {
                            Run updated = run.withStatus(RunStatus.SUCCEEDED, now);
                            store.saveRun(updated);
                        });
        cancelFlags.remove(runId);
        log.debug("Fake workflow completed runId={}", runId);
    }

    private void failRun(UUID runId, Throwable err) {
        if (isCancelled(runId)) {
            return;
        }
        Instant now = Instant.now();
        store.findRun(runId)
                .ifPresent(
                        run -> {
                            Run updated = run.withStatus(RunStatus.FAILED, now);
                            store.saveRun(updated);
                        });
        RuntimeEvent failed =
                RuntimeEvent.of(
                        runId,
                        EventType.RUN_FAILED.wireName(),
                        Map.of("error", err.getMessage() == null ? err.toString() : err.getMessage()),
                        true);
        store.appendEvent(failed);
        eventPublisher.publish(failed);
        cancelFlags.remove(runId);
        log.warn("Fake workflow failed runId={} msg={}", runId, err.toString());
    }

    private boolean isCancelled(UUID runId) {
        AtomicBoolean flag = cancelFlags.get(runId);
        return flag != null && flag.get();
    }
}
