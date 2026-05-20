package io.lifeengine.runtime.agents;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AgentRegistry {

    private final Map<String, AgentExecutor> agents;

    public AgentRegistry(List<AgentExecutor> executors) {
        Map<String, AgentExecutor> map = new LinkedHashMap<>();
        for (AgentExecutor executor : executors) {
            map.put(executor.agentId(), executor);
        }
        this.agents = Map.copyOf(map);
    }

    public AgentExecutor require(String agentId) {
        AgentExecutor executor = agents.get(agentId);
        if (executor == null) {
            throw new IllegalArgumentException("Unknown agent: " + agentId);
        }
        return executor;
    }
}
