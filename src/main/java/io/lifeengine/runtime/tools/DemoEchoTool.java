package io.lifeengine.runtime.tools;

import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Safe demo tool — echoes input; no external side effects. */
@Component
public class DemoEchoTool implements ToolExecutor {

    public static final String TOOL_ID = "demo.echo";

    @Override
    public String toolId() {
        return TOOL_ID;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(TOOL_ID, "Echoes input for workflow tool-stage demos");
    }

    @Override
    public Mono<ToolExecutionResult> execute(ToolExecutionRequest request, WorkflowRunContext ctx) {
        if (ctx.isCancelled()) {
            return Mono.error(new IllegalStateException("Run cancelled"));
        }
        ctx.emit(
                EventType.TOOL_STARTED,
                Map.of("toolId", TOOL_ID, "inputPreview", WorkflowRunContext.truncate(request.input(), 200)),
                false);
        String output = request.input() == null ? "" : request.input();
        ctx.putToolOutput(TOOL_ID, output);
        ctx.emit(
                EventType.TOOL_SUCCEEDED,
                Map.of("toolId", TOOL_ID, "outputPreview", WorkflowRunContext.truncate(output, 200)),
                false);
        return Mono.just(ToolExecutionResult.ok(TOOL_ID, output));
    }
}
