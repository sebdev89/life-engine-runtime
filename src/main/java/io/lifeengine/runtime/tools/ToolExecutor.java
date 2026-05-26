package io.lifeengine.runtime.tools;

import io.lifeengine.runtime.workflow.WorkflowRunContext;
import reactor.core.publisher.Mono;

public interface ToolExecutor {

    String toolId();

    ToolDefinition definition();

    Mono<ToolExecutionResult> execute(ToolExecutionRequest request, WorkflowRunContext ctx);
}
