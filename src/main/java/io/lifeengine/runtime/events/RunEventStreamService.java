package io.lifeengine.runtime.events;

import io.lifeengine.runtime.api.RuntimeEventResponse;
import io.lifeengine.runtime.core.RunStore;
import io.lifeengine.runtime.core.RunNotFoundException;
import io.lifeengine.runtime.domain.RuntimeEvent;
import java.time.Duration;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
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
        return stream(runId, RuntimeEvent.UNASSIGNED_SEQ);
    }

    /**
     * @param afterSeq reanuda estrictamente después de este {@code seq}. {@link
     *     RuntimeEvent#UNASSIGNED_SEQ} entrega el run desde el principio.
     */
    public Flux<ServerSentEvent<RuntimeEventResponse>> stream(UUID runId, long afterSeq) {
        // Defer all RunStore access so the existence check and replay read happen on
        // boundedElastic — never on the Netty event loop that handles the SSE handshake.
        return Flux.defer(() -> buildStream(runId, afterSeq)).subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<ServerSentEvent<RuntimeEventResponse>> buildStream(UUID runId, long afterSeq) {
        if (store.findRun(runId).isEmpty()) {
            return Flux.error(new RunNotFoundException(runId));
        }

        Flux<ServerSentEvent<RuntimeEventResponse>> events =
                Flux.<ServerSentEvent<RuntimeEventResponse>>create(
                                sink -> {
                                    SeqOrderedEmitter emitter = new SeqOrderedEmitter(sink, afterSeq);
                                    // Subscribe before replay so events published during
                                    // handshake are not dropped by the multicast sink.
                                    Disposable live =
                                            publisher.live(runId).subscribe(emitter::offer, sink::error);
                                    store.eventsFor(runId).forEach(emitter::offer);
                                    // El replay terminó: lo que quedó retenido ya no espera a nadie.
                                    emitter.drainPending();
                                    sink.onDispose(live::dispose);
                                })
                        .takeUntil(e -> e.data() != null && e.data().terminal());

        Flux<ServerSentEvent<RuntimeEventResponse>> keepalive =
                Flux.interval(Duration.ofSeconds(15))
                        .map(i -> ServerSentEvent.<RuntimeEventResponse>builder().comment("ping").build())
                        .takeUntilOther(events.ignoreElements());

        return events.mergeWith(keepalive);
    }

    /**
     * Emite en orden estricto de {@code seq}, uniendo el replay del store con el sink en vivo.
     *
     * <p>El problema que resuelve: hay que suscribirse al sink en vivo <b>antes</b> de leer el
     * store —si no, se pierden los eventos publicados durante el handshake— pero eso hace que un
     * evento vivo pueda llegar antes que los históricos que lo preceden. Antes se emitía en orden
     * de llegada y el cliente veía un orden que no era el del log.
     *
     * <p>La solución es retener TODO mientras dura el replay y drenar por {@code seq} al terminar.
     * El buffer existe sólo en esa ventana —acotada y corta— así que no hace falta un techo por
     * tiempo: no hay forma de que quede esperando un evento que nunca llega. Pasado el replay, los
     * eventos vivos ya vienen en orden y se emiten directo.
     */
    private final class SeqOrderedEmitter {

        private final FluxSink<ServerSentEvent<RuntimeEventResponse>> sink;
        private final long afterSeq;
        private final Set<UUID> seen = ConcurrentHashMap.newKeySet();
        private final NavigableMap<Long, RuntimeEvent> pending = new TreeMap<>();
        private boolean replayDone;
        private long lastEmitted;

        SeqOrderedEmitter(FluxSink<ServerSentEvent<RuntimeEventResponse>> sink, long afterSeq) {
            this.sink = sink;
            this.afterSeq = afterSeq;
            this.lastEmitted = afterSeq;
        }

        synchronized void offer(RuntimeEvent event) {
            if (!seen.add(event.eventId())) {
                return;
            }
            // Un evento sin seq no pasó por el log y no tiene lugar en el orden. No debería
            // ocurrir en el camino normal —emit() publica lo que devolvió el store— pero si un
            // publicador nuevo lo hiciera, se emite igual en vez de tragárselo en silencio.
            if (!event.hasSeq()) {
                emit(event);
                return;
            }
            if (event.seq() <= afterSeq) {
                return; // el cliente ya lo vio; reanudó después de este
            }
            if (!replayDone) {
                pending.put(event.seq(), event);
                return;
            }
            if (event.seq() > lastEmitted) {
                emit(event);
            }
        }

        /** Fin del replay: se drena lo retenido en orden y se pasa a emisión directa. */
        synchronized void drainPending() {
            replayDone = true;
            for (RuntimeEvent event : pending.values()) {
                if (event.seq() > lastEmitted) {
                    emit(event);
                }
            }
            pending.clear();
        }

        private void emit(RuntimeEvent event) {
            lastEmitted = Math.max(lastEmitted, event.seq());
            sink.next(toSse(event));
            if (event.terminal()) {
                sink.complete();
            }
        }
    }

    private ServerSentEvent<RuntimeEventResponse> toSse(RuntimeEvent event) {
        return ServerSentEvent.<RuntimeEventResponse>builder()
                // El id del SSE es `seq`, no `eventId`: es lo que permite reanudar con
                // Last-Event-ID de forma exacta. `eventId` sigue viajando en el payload y quedó
                // congelado ahí como contrato (ADR-RT-012).
                .id(String.valueOf(event.seq()))
                .event(event.type())
                .data(RuntimeEventResponse.from(event))
                .build();
    }
}
