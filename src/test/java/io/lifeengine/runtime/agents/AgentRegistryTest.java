package io.lifeengine.runtime.agents;

import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentRegistryTest {

    @Test
    void require_unknownAgent_throwsAgentNotFound() {
        AgentRegistry registry = new AgentRegistry(List.of());
        Assertions.assertThatThrownBy(() -> registry.require("missing-agent"))
                .isInstanceOf(AgentNotFoundException.class)
                .hasMessageContaining("missing-agent");
    }

    @Test
    void register_resolvesAgent() {
        AgentRegistry registry = new AgentRegistry(List.of());
        AgentExecutor stub =
                new AgentExecutor() {
                    @Override
                    public String agentId() {
                        return "stub-agent";
                    }

                    @Override
                    public reactor.core.publisher.Mono<AgentExecutionResult> execute(
                            AgentExecutionRequest request, io.lifeengine.runtime.workflow.WorkflowRunContext ctx) {
                        return reactor.core.publisher.Mono.just(AgentExecutionResult.ok(agentId(), "ok"));
                    }
                };
        registry.register(stub);
        Assertions.assertThat(registry.require("stub-agent").agentId()).isEqualTo("stub-agent");
    }
}
