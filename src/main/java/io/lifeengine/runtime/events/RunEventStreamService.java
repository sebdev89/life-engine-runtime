package io.lifeengine.runtime.events;

import io.lifeengine.runtime.api.RuntimeEventResponse;
import io.lifeengine.runtime.core.RunStore;
import io.lifeengine.runtime.core.RunNotFoundException;
import io.lifeengine.runtime.domain.RuntimeEvent;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Reactive SSE event spine for a single run.
 *
 * <p>Combines the persisted event history (replay) with the in-process live publisher
 * ({@link RunEventPublisher}) into a single ordered {@link ServerSentEvent} stream:
 *
 * <ul>
 *   <li><b>Blocking safety</b> — every {@link RunStore} read (existence check + replay) is
 *       wrapped in {@link Flux#defer(java.util.function.Supplier)} and pinned to
 *       {@link Schedulers#boundedElastic()} so the Netty event-loop that completes the SSE
 *       handshake never executes a blocking call. Locked in by
 *       {@code SseStreamBlockingSafetyTest} and the workflow-side
 *       {@code EventLoopBlockingSafetyTest}.
 *   <li><b>Ordering &amp; deduplication</b> — the live publisher is a replay sink (last 256
 *       events). We subscribe to it <em>before</em> draining the store so any event published
 *       during the handshake is captured by the live consumer (no drop window). A per-event
 *       {@code seen} set keyed by {@code eventId} suppresses the duplicates that the
 *       replay-then-store join naturally produces. As long as publishers call
 *       {@code RunStore.appendEvent} <em>before</em> {@code RunEventPublisher.publish} (which
 *       {@link io.lifeengine.runtime.workflow.WorkflowRunContext#emit} does), the merged
 *       order matches the store's {@code seq}-ordered history.
 *   <li><b>Lifecycle</b> — completes deterministically on the first event flagged terminal
 *       ({@link io.lifeengine.runtime.domain.RuntimeEvent#terminal()}). A 15s keepalive
 *       comment frame is emitted in parallel so idle SSE clients (browsers, proxies) do not
 *       reap the connection; the keepalive flux completes alongside the data flux.
 * </ul>
 */
@Service
public class RunEventStreamService {

    private final RunStore store;
    private final RunEventPublisher publisher;

    public RunEventStreamService(RunStore store, RunEventPublisher publisher) {
        this.store = store;
        this.publisher = publisher;
    }

    public Flux<ServerSentEvent<RuntimeEventResponse>> stream(UUID runId) {
        // Defer all RunStore access so the existence check and replay read happen on
        // boundedElastic — never on the Netty event loop that handles the SSE handshake.
        return Flux.defer(() -> buildStream(runId)).subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<ServerSentEvent<RuntimeEventResponse>> buildStream(UUID runId) {
        if (store.findRun(runId).isEmpty()) {
            return Flux.error(new RunNotFoundException(runId));
        }

        Flux<ServerSentEvent<RuntimeEventResponse>> events =
                Flux.<ServerSentEvent<RuntimeEventResponse>>create(
                                sink -> {
                                    Set<UUID> seen = ConcurrentHashMap.newKeySet();
                                    Consumer<RuntimeEvent> emit =
                                            event -> {
                                                if (!seen.add(event.eventId())) {
                                                    return;
                                                }
                                                sink.next(toSse(event));
                                                if (event.terminal()) {
                                                    sink.complete();
                                                }
                                            };
                                    // Subscribe before replay so events published during
                                    // handshake are not dropped by the multicast sink.
                                    Disposable live =
                                            publisher.live(runId).subscribe(emit, sink::error);
                                    store.eventsFor(runId).forEach(emit);
                                    sink.onDispose(live::dispose);
                                })
                        .takeUntil(e -> e.data() != null && e.data().terminal());

        Flux<ServerSentEvent<RuntimeEventResponse>> keepalive =
                Flux.interval(Duration.ofSeconds(15))
                        .map(i -> ServerSentEvent.<RuntimeEventResponse>builder().comment("ping").build())
                        .takeUntilOther(events.ignoreElements());

        return events.mergeWith(keepalive);
    }

    private ServerSentEvent<RuntimeEventResponse> toSse(RuntimeEvent event) {
        return ServerSentEvent.<RuntimeEventResponse>builder()
                .id(event.eventId().toString())
                .event(event.type())
                .data(RuntimeEventResponse.from(event))
                .build();
    }
}
