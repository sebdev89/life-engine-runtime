package io.lifeengine.runtime.core;

import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.events.RunEventPublisher;
import java.time.Duration;
import java.time.Instant;
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
 * Deterministic fake pipeline: RUN_STARTED → agent-a → agent-b → RUN_COMPLETED.
 */
@Component
public class FakeWorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(FakeWorkflowExecutor.class);

    private final InMemoryRunStore store;
    private final RunEventPublisher eventPublisher;
    private final Duration stepDelay;
    private final ConcurrentHashMap<UUID, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Disposable> activeJobs = new ConcurrentHashMap<>();

    public FakeWorkflowExecutor(
            InMemoryRunStore store,
            RunEventPublisher eventPublisher,
            @Value("${lifeengine.runtime.fake-workflow.step-delay-ms:50}") long stepDelayMs) {
        this.store = store;
        this.eventPublisher = eventPublisher;
        this.stepDelay = Duration.ofMillis(Math.max(1, stepDelayMs));
    }

    public void schedule(UUID runId) {
        log.info("Starting fake legacy workflow for runId={} (agent-a / agent-b)", runId);
        cancelFlags.put(runId, new AtomicBoolean(false));
        Disposable disposable =
                Flux.concat(
                                step(runId, "RUN_STARTED", Map.of(), false),
                                step(runId, "AGENT_STARTED", Map.of("agentId", "agent-a"), false),
                                step(runId, "AGENT_COMPLETED", Map.of("agentId", "agent-a"), false),
                                step(runId, "AGENT_STARTED", Map.of("agentId", "agent-b"), false),
                                step(runId, "AGENT_COMPLETED", Map.of("agentId", "agent-b"), false),
                                step(runId, "RUN_COMPLETED", Map.of(), true))
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
            return true;
        }
        return flag != null;
    }

    private Mono<RuntimeEvent> step(
            UUID runId, String type, Map<String, String> attributes, boolean terminal) {
        return Mono.defer(
                        () -> {
                            if (isCancelled(runId)) {
                                return Mono.empty();
                            }
                            RuntimeEvent event = RuntimeEvent.of(runId, type, attributes, terminal);
                            store.appendEvent(event);
                            eventPublisher.publish(event);
                            return Mono.just(event);
                        })
                .delayElement(stepDelay);
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
                RuntimeEvent.of(runId, "RUN_FAILED", Map.of("error", err.getMessage()), true);
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
