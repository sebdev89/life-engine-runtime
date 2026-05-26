package io.lifeengine.runtime.workflow;

/** Ordered stage within a workflow definition. */
public record WorkflowStage(String stageId, int order, StageKind kind, String refId) {

    public enum StageKind {
        AGENT,
        TOOL
    }

    public static WorkflowStage agent(String agentId, int order) {
        return new WorkflowStage("stage-" + order, order, StageKind.AGENT, agentId);
    }

    public static WorkflowStage tool(String toolId, int order) {
        return new WorkflowStage("stage-" + order, order, StageKind.TOOL, toolId);
    }
}
