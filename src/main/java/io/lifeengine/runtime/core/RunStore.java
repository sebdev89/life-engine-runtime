package io.lifeengine.runtime.core;

import io.lifeengine.runtime.domain.AgentStageRecord;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.llm.LlmCallRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Run persistence abstraction (in-memory today; Postgres later). */
public interface RunStore {

    void saveRun(Run run);

    Optional<Run> findRun(UUID runId);

    /**
     * Las corridas de un tenant, las más nuevas primero, paginadas por keyset.
     *
     * <p>Keyset y no {@code OFFSET}: {@code runtime_run} crece sin techo, y un OFFSET grande obliga
     * a Postgres a leer y descartar filas que nadie va a mirar. El orden
     * {@code (created_at DESC, id DESC)} calza con {@code idx_runtime_run_tenant_created}, que la
     * migración V3 creó exactamente para esta consulta.
     *
     * <p>{@code createdBefore} y {@code beforeId} son el cursor, y son {@code null} en la primera
     * página. El id va además del instante porque {@code created_at} no es único: dos corridas
     * disparadas en el mismo microsegundo se repetirían —o se saltearían— entre páginas si el
     * cursor sólo llevara la fecha.
     *
     * <p>{@code tenantId} <b>no puede ser null</b>. Una consulta sin scope devolvería corridas de
     * todos los tenants, y eso no es un listado: es una fuga. Quién tiene derecho a pedir qué lo
     * decide el llamador; este método sólo se niega a responder sin scope.
     */
    List<Run> listRuns(String tenantId, int limit, Instant createdBefore, UUID beforeId);

    /**
     * Persiste el evento y lo devuelve con el {@code seq} que le asignó el log.
     *
     * <p>Devuelve el evento en vez de {@code void} porque quien publica en vivo necesita el mismo
     * número que quedó guardado: si publicara el objeto de entrada, el cliente vería por SSE un
     * evento sin lugar en el orden y no podría reanudar. Ver ADR-RT-012.
     *
     * <p>Idempotente por {@code eventId}: reintentar con el mismo id no duplica y devuelve el
     * {@code seq} original.
     */
    RuntimeEvent appendEvent(RuntimeEvent event);

    /**
     * Escribe el evento y la proyección del run en una sola transacción, <b>el evento primero</b>.
     *
     * <p>Es la invariante de ADR-RT-003: ninguna transición de estado se guarda sin que el evento
     * que la justifica esté escrito. Si la escritura de la proyección falla, la transacción se
     * deshace entera y no queda un estado sin su evento.
     */
    RuntimeEvent appendEventAndSaveRun(RuntimeEvent event, Run run);

    List<RuntimeEvent> eventsFor(UUID runId);

    void appendAgentStage(UUID runId, AgentStageRecord stage);

    List<AgentStageRecord> agentStagesFor(UUID runId);

    void appendLlmCallRecord(UUID runId, LlmCallRecord record);

    List<LlmCallRecord> llmCallRecordsFor(UUID runId);
}
