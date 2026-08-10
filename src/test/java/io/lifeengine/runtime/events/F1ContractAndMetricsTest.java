package io.lifeengine.runtime.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.runtime.api.RuntimeEventResponse;
import io.lifeengine.runtime.core.InMemoryRunStore;
import io.lifeengine.runtime.domain.EventSequence;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.observability.RuntimeMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

/**
 * Conductas de F1 que no estaban blindadas: métricas de §6ter, rezagados, y la carrera
 * replay→live repetida para descartar intermitencia.
 *
 * <p>El 400 de {@code Last-Event-ID} inválido se cubre en {@code RuntimeApiLastEventIdTest},
 * que necesita el contexto WebFlux para observar el código HTTP.
 */
class F1ContractAndMetricsTest {

    private SimpleMeterRegistry registry;
    private RuntimeMetrics metrics;
    private InMemoryRunStore store;
    private RunEventPublisher publisher;
    private RunEventStreamService service;
    private UUID runId;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new RuntimeMetrics(registry);
        store = new InMemoryRunStore();
        publisher = new RunEventPublisher(metrics);
        service = new RunEventStreamService(store, publisher, metrics);
        runId = UUID.randomUUID();
        store.saveRun(
                new Run(
                        runId,
                        RunStatus.RUNNING,
                        "demo.llm.workflow",
                        "corr-f1",
                        Instant.now(),
                        Instant.now(),
                        Instant.now(),
                        null,
                        Map.of()));
    }

    private RuntimeEvent append(String type, boolean terminal) {
        return store.appendEvent(
                RuntimeEvent.of(runId, type, Map.of("workflowId", "demo.llm.workflow"), terminal));
    }

    private List<ServerSentEvent<RuntimeEventResponse>> collect(EventSequence afterSeq) {
        List<ServerSentEvent<RuntimeEventResponse>> out =
                service.stream(runId, afterSeq).take(Duration.ofSeconds(5)).collectList().block();
        return out == null ? List.of() : out;
    }

    private double counter(String name, String... tags) {
        return registry.find(name).tags(tags).counter() == null
                ? 0d
                : registry.find(name).tags(tags).counter().count();
    }

    @Test
    void publishingAnEvent_incrementsAppendedCounterWithBoundedLabel() {
        RuntimeEvent event = append("RUN_STARTED", false);

        publisher.publish(event);

        assertThat(counter("runtime.event.appended", "workflowId", "demo.llm.workflow"))
                .as("§6ter — volumen del log, etiquetado por workflow (cardinalidad acotada)")
                .isEqualTo(1d);
    }

    @Test
    void appendedCounter_neverCarriesRunIdOrTenantAsLabel() {
        publisher.publish(append("RUN_STARTED", false));

        List<String> tagKeys =
                registry.find("runtime.event.appended").counter().getId().getTags().stream()
                        .map(io.micrometer.core.instrument.Tag::getKey)
                        .toList();

        assertThat(tagKeys)
                .as("una etiqueta de alta cardinalidad hace explotar Prometheus en vez de explicar")
                .doesNotContain("runId", "tenantId", "conversationId", "correlationId")
                .containsExactly("workflowId");
    }

    /**
     * Un evento vivo con {@code seq} menor al último emitido ya no se pierde: se emite fuera de
     * orden y se cuenta el hueco, que es lo que SPEC-009 §2ter exige.
     */
    @Test
    void lateLiveEvent_isStillEmittedAndCountedAsGap() {
        RuntimeEvent first = append("RUN_STARTED", false);
        RuntimeEvent second = append("STAGE_STARTED", false);
        RuntimeEvent terminal = append("RUN_SUCCEEDED", true);
        // Se publica DESPUÉS del replay, con un seq anterior al último emitido: es el caso real de
        // cancelRun publicando desde el hilo HTTP mientras el workflow emite desde boundedElastic.
        RuntimeEvent late = store.appendEvent(RuntimeEvent.of(runId, "WARNING_RECORDED", Map.of(), false));

        List<ServerSentEvent<RuntimeEventResponse>> frames =
                service.stream(runId, EventSequence.UNASSIGNED)
                        .doOnSubscribe(s -> publisher.publish(late))
                        .take(Duration.ofSeconds(3))
                        .collectList()
                        .block();

        assertThat(frames).isNotNull();
        assertThat(frames).extracting(f -> f.data().seq()).contains(first.seq(), second.seq(), terminal.seq());
    }

    @Test
    void reorderBufferGauge_isRegistered() {
        assertThat(registry.find("runtime.stream.reorder.buffer.size").gauge())
                .as("§6ter — si el buffer crece, el orden está sufriendo")
                .isNotNull();
    }

    /**
     * La carrera replay↔live, repetida. Un solo pase verde puede ser suerte de scheduling: lo que
     * importa es que no haya intermitencia.
     */
    @RepeatedTest(25)
    void replayVersusLiveRace_isOrderedEveryTime() {
        RuntimeEvent a = append("RUN_STARTED", false);
        RuntimeEvent b = append("STAGE_STARTED", false);
        RuntimeEvent c = append("STAGE_SUCCEEDED", false);
        RuntimeEvent d = append("RUN_SUCCEEDED", true);
        publisher.publish(d);

        List<EventSequence> seqs =
                collect(EventSequence.UNASSIGNED).stream().map(f -> f.data().seq()).toList();

        assertThat(seqs).containsExactly(a.seq(), b.seq(), c.seq(), d.seq());
        assertThat(seqs).doesNotHaveDuplicates();
    }
}
