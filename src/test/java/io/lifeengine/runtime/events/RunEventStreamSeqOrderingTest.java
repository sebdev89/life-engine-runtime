package io.lifeengine.runtime.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.runtime.api.RuntimeEventResponse;
import io.lifeengine.runtime.core.InMemoryRunStore;
import io.lifeengine.runtime.domain.EventSequence;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.domain.RuntimeEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

/**
 * Orden observable del stream (ADR-RT-012).
 *
 * <p>El defecto que estos tests fijan: {@code buildStream} se suscribe al sink en vivo ANTES de
 * leer el store —correcto, si no se pierden los eventos del handshake— pero antes emitía en orden
 * de llegada, así que un evento vivo salía antes que los históricos que lo preceden.
 *
 * <p>El sink en vivo es un {@code replay().limit(256)}: publicar antes de suscribirse reproduce
 * exactamente la carrera, de forma determinista y sin dormir el test.
 */
class RunEventStreamSeqOrderingTest {

    private InMemoryRunStore store;
    private RunEventPublisher publisher;
    private RunEventStreamService service;
    private UUID runId;

    @BeforeEach
    void setUp() {
        store = new InMemoryRunStore();
        publisher = new RunEventPublisher(new io.lifeengine.runtime.observability.RuntimeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        service = new RunEventStreamService(store, publisher, new io.lifeengine.runtime.observability.RuntimeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        runId = UUID.randomUUID();
        store.saveRun(
                new Run(
                        runId,
                        RunStatus.RUNNING,
                        "demo.llm.workflow",
                        "corr-order",
                        null,
                        Instant.now(),
                        Instant.now(),
                        Instant.now(),
                        null,
                        Map.of()));
    }

    private RuntimeEvent append(String type, boolean terminal) {
        return store.appendEvent(RuntimeEvent.of(runId, type, Map.of(), terminal));
    }

    private List<ServerSentEvent<RuntimeEventResponse>> collect(EventSequence afterSeq) {
        List<ServerSentEvent<RuntimeEventResponse>> out =
                service.stream(runId, afterSeq).take(Duration.ofSeconds(5)).collectList().block();
        return out == null ? List.of() : out;
    }

    @Test
    void liveEventArrivingDuringReplay_isEmittedInSeqOrder_notArrivalOrder() {
        RuntimeEvent first = append("RUN_STARTED", false);
        RuntimeEvent second = append("STAGE_STARTED", false);
        RuntimeEvent third = append("STAGE_SUCCEEDED", false);
        // Ya persistido y publicado ANTES de abrir el stream: el sink de replay se lo entrega al
        // suscriptor vivo de inmediato, o sea antes de que termine el replay del store.
        RuntimeEvent live = append("RUN_SUCCEEDED", true);
        publisher.publish(live);

        List<EventSequence> seqs = collect(EventSequence.UNASSIGNED).stream()
                .map(sse -> sse.data().seq())
                .toList();

        assertThat(seqs)
                .as("el orden que ve el cliente es el del log, no el de llegada")
                .containsExactly(first.seq(), second.seq(), third.seq(), live.seq());
    }

    @Test
    void sseId_isTheSeq_andEventIdSurvivesInThePayload() {
        RuntimeEvent event = append("RUN_STARTED", false);
        append("RUN_SUCCEEDED", true);

        ServerSentEvent<RuntimeEventResponse> firstFrame = collect(EventSequence.UNASSIGNED).get(0);

        assertThat(firstFrame.id())
                .as("el id del SSE es el seq — es lo que permite reanudar")
                .isEqualTo(event.seq().toString());
        assertThat(firstFrame.data().eventId())
                .as("eventId no desaparece: quedó congelado en el payload")
                .isEqualTo(event.eventId());
        assertThat(firstFrame.data().seq()).isEqualTo(event.seq());
    }

    @Test
    void resumingFromLastEventId_deliversStrictlyWhatComesAfter() {
        RuntimeEvent first = append("RUN_STARTED", false);
        RuntimeEvent second = append("STAGE_STARTED", false);
        RuntimeEvent third = append("RUN_SUCCEEDED", true);

        List<EventSequence> resumed = collect(second.seq()).stream().map(sse -> sse.data().seq()).toList();

        assertThat(resumed)
                .as("ni repite lo ya visto ni se saltea nada de lo que sigue")
                .containsExactly(third.seq());
        assertThat(resumed).doesNotContain(first.seq(), second.seq());
    }

    @Test
    void resumingFromTheLastEvent_deliversNothingAndCompletes() {
        append("RUN_STARTED", false);
        RuntimeEvent terminal = append("RUN_SUCCEEDED", true);

        assertThat(collect(terminal.seq()))
                .as("un cliente al día no recibe duplicados")
                .isEmpty();
    }

    @Test
    void seqIsMonotonicWithinARun_andAssignedByTheStore() {
        RuntimeEvent a = append("RUN_STARTED", false);
        RuntimeEvent b = append("STAGE_STARTED", false);
        RuntimeEvent c = append("RUN_SUCCEEDED", true);

        assertThat(a.hasSeq()).isTrue();
        assertThat(b.seq().isAfter(a.seq())).isTrue();
        assertThat(c.seq().isAfter(b.seq())).isTrue();
    }

    @Test
    void appendingTheSameEventTwice_isIdempotentAndKeepsTheOriginalSeq() {
        RuntimeEvent event = RuntimeEvent.of(runId, "RUN_STARTED", Map.of(), false);

        RuntimeEvent firstWrite = store.appendEvent(event);
        RuntimeEvent secondWrite = store.appendEvent(event);

        assertThat(secondWrite.seq()).isEqualTo(firstWrite.seq());
        assertThat(store.eventsFor(runId)).hasSize(1);
    }
}
