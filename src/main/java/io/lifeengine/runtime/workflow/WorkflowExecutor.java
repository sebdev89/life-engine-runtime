package io.lifeengine.runtime.workflow;

import java.util.UUID;

/** Starts and cancels asynchronous workflow execution for a run. */
public interface WorkflowExecutor {

    void schedule(UUID runId, WorkflowDefinition definition, String input, String correlationId);

    boolean requestCancel(UUID runId);
}
