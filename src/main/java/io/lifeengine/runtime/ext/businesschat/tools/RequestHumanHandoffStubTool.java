package io.lifeengine.runtime.ext.businesschat.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lifeengine.runtime.tools.ToolDefinition;
import io.lifeengine.runtime.tools.ToolExecutionRequest;
import io.lifeengine.runtime.tools.ToolExecutionResult;
import io.lifeengine.runtime.tools.ToolExecutor;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Stub — records human handoff escalation without operator inbox integration. */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.business-chat.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RequestHumanHandoffStubTool implements ToolExecutor {

    public static final String TOOL_ID = "business-chat.requestHumanHandoff";

    private final ObjectMapper mapper;

    public RequestHumanHandoffStubTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String toolId() {
        return TOOL_ID;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(TOOL_ID, "Escalate conversation to a human operator (stub).");
    }

    @Override
    public Mono<ToolExecutionResult> execute(ToolExecutionRequest request, WorkflowRunContext ctx) {
        ObjectNode input = BusinessChatStubToolSupport.parseInput(mapper, request.input());
        if (!input.hasNonNull("conversationId") || input.get("conversationId").asText().isBlank()) {
            return Mono.just(ToolExecutionResult.failed(TOOL_ID, "missing_conversation_id"));
        }
        ObjectNode output = mapper.createObjectNode();
        output.put("status", "PENDING");
        output.put("handoffId", "stub-handoff-pending");
        return BusinessChatStubToolSupport.executeStub(TOOL_ID, request.input(), mapper, ctx, output);
    }
}
