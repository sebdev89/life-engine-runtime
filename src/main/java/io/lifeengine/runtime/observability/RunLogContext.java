package io.lifeengine.runtime.observability;

import java.util.Map;
import org.slf4j.MDC;

/** Structured log context for runtime execution (correlationId, runId, workflowId). */
public final class RunLogContext {

    public static final String RUN_ID = "runId";
    public static final String WORKFLOW_ID = "workflowId";

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
