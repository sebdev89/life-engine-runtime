package io.lifeengine.runtime.agents;

import io.lifeengine.runtime.workflow.WorkflowRunContext;
import reactor.core.publisher.Mono;

public interface AgentExecutor {

    String agentId();

    Mono<AgentExecutionResult> execute(AgentExecutionRequest request, WorkflowRunContext ctx);
}
