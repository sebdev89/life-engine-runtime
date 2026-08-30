package io.lifeengine.runtime.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Orden de un evento dentro del log — value object congelado en SPEC-009 §2bis.
 *
 * <p>Existe por una invariante, no por ceremonia: el 0 significa "todavía no pasó por el log" y
 * NUNCA es un lugar válido en el orden. Con un {@code long} suelto esa distinción vive en la
 * cabeza de quien lee; acá la impone el tipo, y {@link #isAssigned()} es la única forma de
 * preguntarla.
 *
 * <p>Serializa como número plano ({@link JsonValue}), así que el contrato en el cable no cambia:
 * el cliente ve {@code "seq": 1136}, no un objeto.
 */
public record EventSequence(long value) implements Comparable<EventSequence> {

    /** Un evento que todavía no ocupa lugar en el orden. */
    public static final EventSequence UNASSIGNED = new EventSequence(0L);

    public EventSequence {
        if (value < 0) {
            throw new IllegalArgumentException("event sequence cannot be negative: " + value);
        }
    }

    public static EventSequence of(long value) {
        return value <= 0 ? UNASSIGNED : new EventSequence(value);
    }

    /** True cuando el evento ya ocupa un lugar en el orden del log. */
    public boolean isAssigned() {
        return value > 0;
    }

    public boolean isAfter(EventSequence other) {
        return value > other.value;
    }

    @JsonValue
    public long asLong() {
        return value;
    }

    @Override
    public int compareTo(EventSequence other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
