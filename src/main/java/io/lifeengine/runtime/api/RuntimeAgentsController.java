package io.lifeengine.runtime.api;

import io.lifeengine.runtime.agents.AgentRegistry;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/runtime/agents")
public class RuntimeAgentsController {

    private final AgentRegistry agentRegistry;

    public RuntimeAgentsController(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    @GetMapping
    public Mono<List<AgentListView>> listAgents() {
        return Mono.fromCallable(
                () ->
                        agentRegistry.agents().stream()
                                .map(
                                        agent ->
                                                new AgentListView(
                                                        agent.agentId(),
                                                        agent.capabilities()))
                                .toList());
    }

    public record AgentListView(String agentId, Set<String> capabilities) {}
}
