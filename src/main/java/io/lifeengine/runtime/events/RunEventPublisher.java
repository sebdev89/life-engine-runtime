package io.lifeengine.runtime.events;

import io.lifeengine.runtime.domain.RuntimeEvent;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * In-process bus for live {@link RuntimeEvent} fan-out.
 *
 * <p>Two complementary sinks are maintained:
 *
 * <ul>
 *   <li><b>Per-run sink</b> ({@link #live(UUID)}) — replay-multicast keyed by {@code runId}; used
 *       by {@code RunEventStreamService} to power {@code /api/runtime/runs/{id}/stream}, where
 *       late subscribers must catch up on the run history. The replay buffer is bounded
 *       ({@code limit(256)}) so a long-running cockpit doesn't accumulate unbounded memory.
 *   <li><b>Global sink</b> ({@link #globalLive()}) — a single multicast (no replay) carrying
 *       every event from every run, used by {@code GlobalEventStreamService} to power
 *       {@code /api/runtime/events/stream} (Mission Control / Crypto Watch). Replay is
 *       intentionally OFF on the global sink: the global spine is a "tail -f" of live runtime
 *       activity. Operators that need history use the per-run stream instead.
 * </ul>
 *
 * <p>Adding the global sink here — rather than at every emission site — keeps
 * {@code WorkflowRunContext.emit} (the single emission funnel) and {@code RunService.cancelRun}
 * unchanged: all events automatically fan out to both sinks.
 */
@Component
public class RunEventPublisher {

    private final ConcurrentHashMap<UUID, Sinks.Many<RuntimeEvent>> sinks = new ConcurrentHashMap<>();

    /**
     * Multicast sink with no replay. Subscribers only see events that arrive AFTER they
     * subscribe — that's exactly the Mission Control semantic ("show me what's happening from
     * now on"). {@code multicast().directBestEffort()} drops events for slow consumers instead
     * of stalling the publish path; the runtime publish path must never block on a slow SSE
     * client.
     */
    private final Sinks.Many<RuntimeEvent> globalSink =
            Sinks.many().multicast().directBestEffort();

    public void publish(RuntimeEvent event) {
        sinkFor(event.runId()).tryEmitNext(event);
        globalSink.tryEmitNext(event);
    }

    public Flux<RuntimeEvent> live(UUID runId) {
        return sinkFor(runId).asFlux();
    }

    /**
     * Live, replay-free fan-out of every {@link RuntimeEvent} from every run. Subscribers see
     * events from the moment they subscribe forward.
     */
    public Flux<RuntimeEvent> globalLive() {
        return globalSink.asFlux();
    }

    private Sinks.Many<RuntimeEvent> sinkFor(UUID runId) {
        return sinks.computeIfAbsent(
                runId,
                id -> Sinks.many().replay().limit(256));
    }
}
