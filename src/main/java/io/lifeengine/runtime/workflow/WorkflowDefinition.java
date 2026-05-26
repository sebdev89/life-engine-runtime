package io.lifeengine.runtime.workflow;

import java.time.Duration;
import java.util.List;

/**
 * Deterministic workflow definition: ordered stages, contracts, policy metadata.
 * Execution is driven by {@link DefinitionDrivenWorkflowExecutor} — no silent fallback.
 */
public record WorkflowDefinition(
        String workflowId,
        String inputContract,
        String outputContract,
        List<WorkflowStage> stages,
        Duration stageTimeout,
        String description) {

    public WorkflowDefinition {
        stages = stages == null ? List.of() : List.copyOf(stages);
        stageTimeout = stageTimeout == null ? Duration.ofMinutes(5) : stageTimeout;
    }

    public static WorkflowDefinition llmDemo(String workflowId, List<WorkflowStage> agentStages) {
        return new WorkflowDefinition(
                workflowId,
                "runtime.text.v1",
                "runtime.classification.v1",
                agentStages,
                Duration.ofMinutes(5),
                "Summarizer → Classifier LLM agent chain (demo MLP)");
    }
}
