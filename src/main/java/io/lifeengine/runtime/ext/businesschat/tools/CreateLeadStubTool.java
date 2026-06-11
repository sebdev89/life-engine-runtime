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

/** Stub — records lead capture without CRM integration. */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.business-chat.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CreateLeadStubTool implements ToolExecutor {

    public static final String TOOL_ID = "business-chat.createLead";

    private final ObjectMapper mapper;

    public CreateLeadStubTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String toolId() {
        return TOOL_ID;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(TOOL_ID, "Persist a captured lead for follow-up (stub).");
    }

    @Override
    public Mono<ToolExecutionResult> execute(ToolExecutionRequest request, WorkflowRunContext ctx) {
        ObjectNode input = BusinessChatStubToolSupport.parseInput(mapper, request.input());
        if (!input.has("lead") || input.get("lead").isNull()) {
            return Mono.just(ToolExecutionResult.failed(TOOL_ID, "missing_lead_data"));
        }
        ObjectNode output = mapper.createObjectNode();
        output.put("captured", true);
        output.put("leadId", "stub-lead-pending");
        return BusinessChatStubToolSupport.executeStub(TOOL_ID, request.input(), mapper, ctx, output);
    }
}
