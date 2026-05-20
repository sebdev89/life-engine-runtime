package io.lifeengine.runtime.events;

import io.lifeengine.runtime.api.RuntimeEventResponse;
import io.lifeengine.runtime.core.InMemoryRunStore;
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

@Service
public class RunEventStreamService {

    private final InMemoryRunStore store;
    private final RunEventPublisher publisher;

    public RunEventStreamService(InMemoryRunStore store, RunEventPublisher publisher) {
        this.store = store;
        this.publisher = publisher;
    }

    public Flux<ServerSentEvent<RuntimeEventResponse>> stream(UUID runId) {
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
