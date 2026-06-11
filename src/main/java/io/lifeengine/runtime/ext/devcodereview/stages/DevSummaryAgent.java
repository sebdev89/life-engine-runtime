package io.lifeengine.runtime.ext.devcodereview.stages;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.agents.LlmAgentSupport;
import io.lifeengine.runtime.agents.StrictAgentJson;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.ext.devcodereview.DevCodeReviewPrompts;
import io.lifeengine.runtime.llm.LlmClient;
import io.lifeengine.runtime.llm.LlmMessage;
import io.lifeengine.runtime.prompts.PromptTemplate;
import io.lifeengine.runtime.prompts.PromptTemplateRegistry;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Stage 2 — operator-facing summary. Merges the original request with stage-1 findings into the
 * final {@code {severity, summary, recommendations}} contract.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.dev-code-review.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DevSummaryAgent implements AgentExecutor {

    public static final String AGENT_ID = "dev-summary-agent";

    private final LlmClient llmClient;
    private final ObjectMapper mapper;
    private final PromptTemplateRegistry promptTemplateRegistry;

    public DevSummaryAgent(
            LlmClient llmClient, ObjectMapper mapper, PromptTemplateRegistry promptTemplateRegistry) {
        this.llmClient = llmClient;
        this.mapper = mapper;
        this.promptTemplateRegistry = promptTemplateRegistry;
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public Mono<AgentExecutionResult> execute(AgentExecutionRequest request, WorkflowRunContext ctx) {
        if (ctx.isCancelled()) {
            return Mono.error(new IllegalStateException("Run cancelled"));
        }
        ctx.emit(EventType.AGENT_STARTED, Map.of("agentId", AGENT_ID), false);

        String codeReview = request.input() == null || request.input().isBlank() ? "{}" : request.input();
        String userInput;
        try {
            var combined = mapper.createObjectNode();
            combined.set("source", mapper.readTree(ctx.input()));
            combined.set("codeReview", mapper.readTree(codeReview));
            userInput = mapper.writeValueAsString(combined);
        } catch (Exception e) {
            return agentFailed(ctx, e);
        }

        PromptTemplate template =
                promptTemplateRegistry.require(DevCodeReviewPrompts.SUMMARY_ID, DevCodeReviewPrompts.VERSION_V1);
        List<LlmMessage> messages =
                List.of(
                        new LlmMessage("system", template.systemMessage()),
                        new LlmMessage("user", userInput));

        return LlmAgentSupport.callLlm(ctx, request.stageId(), AGENT_ID, llmClient, messages, template)
                .flatMap(
                        response -> {
                            try {
                                StrictAgentJson.DevSummaryOutput summary =
                                        StrictAgentJson.parseDevSummary(response.content());
                                String canonical = StrictAgentJson.canonicalJson(response.content());
                                ctx.putAgentOutput(AGENT_ID, canonical);

                                Map<String, String> attrs = new LinkedHashMap<>();
                                attrs.put("agentId", AGENT_ID);
                                attrs.put("severity", summary.severity());
                                attrs.put(
                                        "recommendationsCount",
                                        Integer.toString(summary.recommendations().size()));
                                attrs.put("summaryPreview", WorkflowRunContext.truncate(summary.summary(), 500));
                                attrs.put("structured", WorkflowRunContext.truncate(canonical, 500));
                                ctx.emit(EventType.AGENT_SUCCEEDED, attrs, false);

                                return Mono.just(AgentExecutionResult.ok(AGENT_ID, canonical));
                            } catch (IllegalArgumentException e) {
                                return agentParseFailed(ctx, e);
                            }
                        })
                .onErrorResume(
                        error -> {
                            if (error instanceof IllegalArgumentException) {
                                return Mono.error(error);
                            }
                            return agentFailed(ctx, error);
                        });
    }

    private Mono<AgentExecutionResult> agentParseFailed(WorkflowRunContext ctx, IllegalArgumentException e) {
        String msg = AGENT_ID + ": " + e.getMessage();
        ctx.emit(EventType.AGENT_FAILED, Map.of("agentId", AGENT_ID, "error", msg), false);
        return Mono.error(new IllegalArgumentException(msg, e));
    }

    private Mono<AgentExecutionResult> agentFailed(WorkflowRunContext ctx, Throwable error) {
        String msg = error.getMessage() == null ? error.toString() : error.getMessage();
        ctx.emit(EventType.AGENT_FAILED, Map.of("agentId", AGENT_ID, "error", msg), false);
        return Mono.error(error);
    }
}
