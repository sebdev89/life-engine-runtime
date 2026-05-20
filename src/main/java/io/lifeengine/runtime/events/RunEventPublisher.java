package io.lifeengine.runtime.events;

import io.lifeengine.runtime.domain.RuntimeEvent;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** In-process bus for live {@link RuntimeEvent} fan-out per run. */
@Component
public class RunEventPublisher {

    private final ConcurrentHashMap<UUID, Sinks.Many<RuntimeEvent>> sinks = new ConcurrentHashMap<>();

    public void publish(RuntimeEvent event) {
        sinkFor(event.runId()).tryEmitNext(event);
    }

    public Flux<RuntimeEvent> live(UUID runId) {
        return sinkFor(runId).asFlux();
    }

    private Sinks.Many<RuntimeEvent> sinkFor(UUID runId) {
        return sinks.computeIfAbsent(
                runId,
                id -> Sinks.many().replay().limit(256));
    }
}
