package io.lifeengine.runtime.workflow;

/** Ordered agent stage within a workflow definition. */
public record WorkflowStage(String agentId, int order) {}
