package io.lifeengine.runtime.agents;

import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.Set;
import reactor.core.publisher.Mono;

public interface AgentExecutor {

    String agentId();

    default Set<String> capabilities() {
        return Set.of("execute");
    }

    Mono<AgentExecutionResult> execute(AgentExecutionRequest request, WorkflowRunContext ctx);
}
