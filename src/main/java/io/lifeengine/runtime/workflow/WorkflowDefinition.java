package io.lifeengine.runtime.workflow;

import java.util.List;

public record WorkflowDefinition(String workflowId, List<WorkflowStage> stages) {}
