package io.lifeengine.runtime.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Cursor de paginación: el {@code (created_at, id)} de la última corrida devuelta.
 *
 * <p>Opaco a propósito, en base64url. Si el cliente pudiera leerlo o construirlo, el orden de la
 * consulta se volvería contrato público: cambiarlo —o agregarle un tercer criterio— rompería a
 * cualquiera que lo hubiese interpretado. Acá es un token que se devuelve tal como vino.
 *
 * <p>Lleva el id además del instante porque {@code created_at} no es único. Dos corridas creadas
 * en el mismo microsegundo se repetirían o se saltearían entre páginas si el cursor sólo llevara
 * la fecha.
 */
public record RunPageCursor(Instant createdAt, UUID runId) {

    private static final String SEPARATOR = "|";

    public String encode() {
        String raw = createdAt.toString() + SEPARATOR + runId;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws InvalidCursorException si el valor no fue emitido por {@link #encode()}. Se distingue
     *     de "no hay cursor" —que es la primera página, y se pide con el parámetro ausente— porque
     *     un cursor ilegible es un error del llamador y merece un 400, no una primera página
     *     silenciosa que le haga creer que llegó al principio de la lista.
     */
    public static RunPageCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int sep = raw.lastIndexOf(SEPARATOR);
            if (sep < 0) {
                throw new InvalidCursorException("el cursor no tiene separador");
            }
            return new RunPageCursor(
                    Instant.parse(raw.substring(0, sep)), UUID.fromString(raw.substring(sep + 1)));
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new InvalidCursorException("cursor ilegible");
        }
    }

    /** El cursor que continúa después de esta página, o {@code null} si no hay más. */
    public static String nextAfter(List<RunSummaryView> page, int limit) {
        if (page.size() < limit) {
            return null;
        }
        RunSummaryView last = page.get(page.size() - 1);
        return new RunPageCursor(last.createdAt(), last.id()).encode();
    }

    /** Cursor inválido: el llamador mandó algo que este servicio no emitió. */
    public static class InvalidCursorException extends RuntimeException {
        public InvalidCursorException(String message) {
            super(message);
        }
    }
}
