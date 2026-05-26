package io.lifeengine.runtime.api;

/** Entry in GET /api/runtime/workflows. */
public record WorkflowListView(String workflowId, String description, String inputContract, String outputContract) {}
