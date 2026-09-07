package io.lifeengine.runtime.observability;

import java.util.Map;
import org.slf4j.MDC;

/**
 * Contexto de log para los tramos IMPERATIVOS de una corrida (correlationId, runId, workflowId).
 *
 * <p>Las claves son las de {@link LogContext}: hay un solo juego de nombres, y cambiar uno acá sin
 * cambiarlo allá haría que el patrón de log imprima un campo que nadie escribe.
 *
 * <p>Esta clase escribe el MDC a mano y sigue siendo correcta donde se usa: bloques sincrónicos
 * dentro de un {@code Mono.fromCallable}, siempre con {@code try/finally}, sin cruzar hilos. Lo que
 * NO hace —y no debe hacer— es limpiar el MDC entero: {@link #clearRun()} quita sus dos claves y
 * ninguna más, para no llevarse puestos el {@code traceId} y el {@code spanId}.
 *
 * <p>Para todo lo reactivo el camino es el Reactor Context, que {@link LogContext} proyecta al MDC
 * en el hilo que corresponda.
 */
public final class RunLogContext {

    public static final String RUN_ID = LogContext.RUN_ID;
    public static final String WORKFLOW_ID = LogContext.WORKFLOW_ID;

    private RunLogContext() {}

    public static void put(String correlationId, String runId, String workflowId) {
        if (correlationId != null && !correlationId.isBlank()) {
            MDC.put(RequestCorrelationWebFilter.CORRELATION_ID_KEY, correlationId);
        }
        if (runId != null && !runId.isBlank()) {
            MDC.put(RUN_ID, runId);
        }
        if (workflowId != null && !workflowId.isBlank()) {
            MDC.put(WORKFLOW_ID, workflowId);
        }
    }

    public static void clearRun() {
        MDC.remove(RUN_ID);
        MDC.remove(WORKFLOW_ID);
    }

    public static Map<String, String> snapshot(String correlationId, String runId, String workflowId) {
        return Map.of(
                RequestCorrelationWebFilter.CORRELATION_ID_KEY,
                correlationId == null ? "" : correlationId,
                RUN_ID,
                runId == null ? "" : runId,
                WORKFLOW_ID,
                workflowId == null ? "" : workflowId);
    }
}
