package io.lifeengine.runtime.agents;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AgentRegistry {

    private final Map<String, AgentExecutor> agents = new LinkedHashMap<>();

    public AgentRegistry(List<AgentExecutor> executors) {
        for (AgentExecutor executor : executors) {
            agents.put(executor.agentId(), executor);
        }
    }

    public void register(AgentExecutor executor) {
        agents.put(executor.agentId(), executor);
    }

    public AgentExecutor require(String agentId) {
        AgentExecutor executor = agents.get(agentId);
        if (executor == null) {
            throw new AgentNotFoundException(agentId);
        }
        return executor;
    }

    public Collection<AgentExecutor> agents() {
        return List.copyOf(agents.values());
    }

    public Map<String, Set<String>> capabilitiesByAgent() {
        Map<String, Set<String>> caps = new LinkedHashMap<>();
        for (AgentExecutor agent : agents.values()) {
            caps.put(agent.agentId(), agent.capabilities());
        }
        return Map.copyOf(caps);
    }
}
