package io.lifeengine.runtime.api;

import io.lifeengine.runtime.domain.Run;
import java.time.Instant;
import java.util.UUID;

/**
 * Una corrida vista desde un listado.
 *
 * <p><b>No lleva {@code metadata} a propósito.</b> En las corridas reales de
 * {@code business-chat.reply.v1} ese mapa tiene la clave {@code input}, que es el mensaje que
 * escribió una persona. Un listado se pide de a veinte y se pinta en una pantalla: no es lugar
 * para el contenido de las conversaciones. Quien necesite el detalle pide
 * {@code GET /api/runtime/runs/{id}}, que pasa por {@link SecretRedactor}.
 *
 * <p>Tampoco lleva {@code tenantId}: el listado ya viene scopeado al tenant del token, así que
 * repetirlo en cada fila no agrega información y sí agrega una forma de filtrarlo en el cliente,
 * que es exactamente donde no queremos que viva esa decisión.
 */
public record RunSummaryView(
        UUID id,
        String status,
        String workflowId,
        String correlationId,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt) {

    public static RunSummaryView from(Run run) {
        return new RunSummaryView(
                run.id(),
                run.status().name(),
                run.workflowId(),
                run.correlationId(),
                run.createdAt(),
                run.updatedAt(),
                run.startedAt(),
                run.finishedAt());
    }
}
