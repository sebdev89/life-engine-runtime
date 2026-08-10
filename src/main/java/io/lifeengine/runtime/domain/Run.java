package io.lifeengine.runtime.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Agregado de una corrida.
 *
 * <p>{@code tenantId} (W1-05, TD-TENANCY-001) es <b>de quién es la corrida</b>, y sale del token
 * del llamador — nunca del cuerpo del request. Runtime no lo valida ni autoriza sobre él: eso es
 * de Auth y del vertical. Acá sólo se registra, que es lo que faltaba: las 79 corridas de
 * producción tienen tres claves en `metadata` (input, executor, conversationId) y ninguna dice a
 * qué tenant pertenecen, así que no hay forma de auditar ni de filtrar por tenant.
 *
 * <p>Puede ser {@code null}: los tokens de servicio no llevan tenant todavía (es W2, con
 * {@code act.tenant}), y una corrida disparada por uno queda sin atribuir. Se cuenta en
 * {@code runtime_tenancy_missing_claim_total} en vez de rechazarse — rechazar rompería hoy todo
 * el tráfico S2S, que es el 100% del tráfico real.
 */
public record Run(
        UUID id,
        RunStatus status,
        String workflowId,
        String correlationId,
        String tenantId,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> metadata) {

    public Run {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public Run withStatus(RunStatus newStatus, Instant now) {
        return new Run(
                id,
                newStatus,
                workflowId,
                correlationId,
                tenantId,
                createdAt,
                now,
                startedAt != null ? startedAt : (newStatus == RunStatus.RUNNING ? now : null),
                newStatus.isTerminal() ? now : finishedAt,
                metadata);
    }

    public Run withStartedAt(Instant started) {
        return new Run(
                id,
                status,
                workflowId,
                correlationId,
                tenantId,
                createdAt,
                updatedAt,
                started,
                finishedAt,
                metadata);
    }

    public Run withMetadata(Map<String, Object> newMetadata) {
        return new Run(
                id,
                status,
                workflowId,
                correlationId,
                tenantId,
                createdAt,
                updatedAt,
                startedAt,
                finishedAt,
                newMetadata);
    }
}
