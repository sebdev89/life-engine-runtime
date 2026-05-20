package io.lifeengine.runtime.core;

/** Raised when {@code workflowId} is not a known runtime workflow. */
public class UnknownWorkflowException extends RuntimeException {

    private final String workflowId;

    public UnknownWorkflowException(String workflowId) {
        super("Unknown workflowId: " + workflowId);
        this.workflowId = workflowId;
    }

    public String workflowId() {
        return workflowId;
    }
}
