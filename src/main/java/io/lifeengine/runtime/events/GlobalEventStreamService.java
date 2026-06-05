package io.lifeengine.runtime.events;

import io.lifeengine.runtime.api.RuntimeEventResponse;
import io.lifeengine.runtime.domain.RuntimeEvent;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Reactive SSE bridge for the Global Runtime Event Spine.
 *
 * <p>Backs {@code GET /api/runtime/events/stream}. Unlike {@link RunEventStreamService}, this
 * service does <em>not</em> read from {@code RunStore} — the global spine is intentionally
 * "live only" so a freshly opened Mission Control sees everything from now on without
 * paginating through the entire run history of every workflow that ever ran. Operators that
 * need a per-run replay open the per-run stream by following the {@code runId} on the spine.
 *
 * <p>Three optional filters are honoured (combined with AND):
 *
 * <ul>
 *   <li>{@code workflowId} — exact match (e.g. {@code crypto.market-review.v1}).
 *   <li>{@code workflowPrefix} — prefix match (e.g. {@code crypto.}); typical for vertical
 *       dashboards that span every flavour of a workflow family.
 *   <li>{@code runId} — exact match. Mostly a debugging shortcut; the per-run stream is
 *       usually the right tool when the operator already knows the run.
 * </ul>
 *
 * <p>A 15-second keepalive comment frame matches {@link RunEventStreamService}, so browser
 * EventSource clients and intermediaries do not reap the connection during quiet periods.
 */
@Service
public class GlobalEventStreamService {

    private static final Duration KEEPALIVE = Duration.ofSeconds(15);

    private final RunEventPublisher publisher;

    public GlobalEventStreamService(RunEventPublisher publisher) {
        this.publisher = publisher;
    }

    public Flux<ServerSentEvent<RuntimeEventResponse>> stream(GlobalEventFilter filter) {
        Predicate<RuntimeEvent> matcher = matcherFor(filter);

        Flux<ServerSentEvent<RuntimeEventResponse>> events =
                publisher.globalLive()
                        .filter(matcher)
                        .map(GlobalEventStreamService::toSse);

        // Welcome comment is critical for two reasons:
        //   1. Reactor-Netty doesn't flush response headers until the first body byte arrives;
        //      a silent SSE stream causes WebTestClient (and conservative HTTP intermediaries)
        //      to time out before they ever see the 200 OK.
        //   2. Browser EventSource flips to {@code OPEN} on first byte, giving Mission Control
        //      an immediate "LIVE" chip instead of a 15-second "CONNECTING" delay until the
        //      first keepalive.
        Flux<ServerSentEvent<RuntimeEventResponse>> welcome =
                Flux.just(ServerSentEvent.<RuntimeEventResponse>builder().comment("welcome").build());

        Flux<ServerSentEvent<RuntimeEventResponse>> keepalive =
                Flux.interval(KEEPALIVE)
                        .map(i -> ServerSentEvent.<RuntimeEventResponse>builder().comment("ping").build());

        // Merge keeps the global spine open indefinitely. Cancellation happens when the SSE
        // client disconnects, which Reactor maps to the upstream subscription being disposed.
        return welcome.concatWith(events.mergeWith(keepalive));
    }

    private static Predicate<RuntimeEvent> matcherFor(GlobalEventFilter filter) {
        Predicate<RuntimeEvent> p = e -> true;
        Optional<String> workflowId = filter.workflowId();
        if (workflowId.isPresent()) {
            String wf = workflowId.get();
            p = p.and(e -> wf.equals(workflowIdOf(e)));
        }
        Optional<String> prefix = filter.workflowPrefix();
        if (prefix.isPresent()) {
            String pfx = prefix.get();
            p = p.and(e -> {
                String wf = workflowIdOf(e);
                return wf != null && wf.startsWith(pfx);
            });
        }
        Optional<UUID> runId = filter.runId();
        if (runId.isPresent()) {
            UUID rid = runId.get();
            p = p.and(e -> rid.equals(e.runId()));
        }
        return p;
    }

    private static String workflowIdOf(RuntimeEvent event) {
        return event.attributes() == null ? null : event.attributes().get("workflowId");
    }

    private static ServerSentEvent<RuntimeEventResponse> toSse(RuntimeEvent event) {
        return ServerSentEvent.<RuntimeEventResponse>builder()
                .id(event.eventId().toString())
                .event(event.type())
                .data(RuntimeEventResponse.from(event))
                .build();
    }
}
