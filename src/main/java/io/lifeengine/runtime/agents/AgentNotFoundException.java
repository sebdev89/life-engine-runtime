package io.lifeengine.runtime.agents;

/** Raised when no agent is registered for the requested id. */
public class AgentNotFoundException extends RuntimeException {

    private final String agentId;

    public AgentNotFoundException(String agentId) {
        super("Unknown agent: " + agentId);
        this.agentId = agentId;
    }

    public String agentId() {
        return agentId;
    }
}
