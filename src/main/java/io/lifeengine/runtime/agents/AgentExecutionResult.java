package io.lifeengine.runtime.agents;

public record AgentExecutionResult(
        String agentId,
        boolean success,
        String output,
        String classification,
        String error) {

    public static AgentExecutionResult ok(String agentId, String output) {
        return new AgentExecutionResult(agentId, true, output, null, null);
    }

    public static AgentExecutionResult okWithClassification(
            String agentId, String output, String classification) {
        return new AgentExecutionResult(agentId, true, output, classification, null);
    }

    public static AgentExecutionResult failed(String agentId, String error) {
        return new AgentExecutionResult(agentId, false, null, null, error);
    }
}
