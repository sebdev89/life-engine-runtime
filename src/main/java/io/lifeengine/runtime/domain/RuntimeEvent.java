package io.lifeengine.runtime.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only runtime event for a single run.
 *
 * <p>{@code seq} es el orden observable del log — monotónico dentro de un run. Lo asigna la
 * persistencia, no el dominio: un evento recién construido vale {@link #UNASSIGNED_SEQ} hasta que
 * {@code RunStore.appendEvent} lo devuelve con su número.
 *
 * <p>El orden NO se deriva de {@code occurredAt}: dos eventos pueden compartir milisegundo, así que
 * el timestamp no da un orden total ni reproducible. Ver ADR-RT-012.
 */
public record RuntimeEvent(
        UUID eventId,
        UUID runId,
        String type,
        Instant occurredAt,
        String source,
        Map<String, String> attributes,
        boolean terminal,
        EventSequence seq) {

    public RuntimeEvent {
        type = type == null ? "" : type.strip();
        source = source == null ? "runtime-core" : source.strip();
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        seq = seq == null ? EventSequence.UNASSIGNED : seq;
    }

    public static RuntimeEvent of(UUID runId, String type, Map<String, String> attributes, boolean terminal) {
        return new RuntimeEvent(
                UUID.randomUUID(),
                runId,
                type,
                Instant.now(),
                "runtime-core",
                attributes,
                terminal,
                EventSequence.UNASSIGNED);
    }

    /** Copia con el número de orden que asignó la persistencia. */
    public RuntimeEvent withSeq(EventSequence assignedSeq) {
        return new RuntimeEvent(
                eventId, runId, type, occurredAt, source, attributes, terminal, assignedSeq);
    }

    /** True cuando el evento ya ocupa un lugar en el orden del log. */
    public boolean hasSeq() {
        return seq.isAssigned();
    }
}
