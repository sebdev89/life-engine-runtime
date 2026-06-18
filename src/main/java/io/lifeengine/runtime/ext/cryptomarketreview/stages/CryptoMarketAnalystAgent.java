package io.lifeengine.runtime.ext.cryptomarketreview.stages;

import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.agents.LlmAgentSupport;
import io.lifeengine.runtime.agents.StrictAgentJson;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.ext.cryptomarketreview.CryptoMarketReviewPrompts;
import io.lifeengine.runtime.llm.LlmClient;
import io.lifeengine.runtime.llm.LlmMessage;
import io.lifeengine.runtime.prompts.PromptTemplate;
import io.lifeengine.runtime.prompts.PromptTemplateRegistry;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Stage 3 — LLM-backed market analyst. Reads the {@code marketContext} from stage 2 (passed in
 * as {@code request.input()}) and emits a strict-JSON analysis with bias / setup / invalidations
 * / confidence / risks. The system prompt is intentionally cautious: the analyst does NOT issue
 * direct buy/sell instructions — that role belongs to the risk-review stage.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.crypto-market-review.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CryptoMarketAnalystAgent implements AgentExecutor {

    public static final String AGENT_ID = "crypto-market-analyst-agent";

    private final LlmClient llmClient;
    private final PromptTemplateRegistry promptTemplateRegistry;

    public CryptoMarketAnalystAgent(
            @Qualifier("smartLlmClient") LlmClient llmClient, PromptTemplateRegistry promptTemplateRegistry) {
        this.llmClient = llmClient;
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

        PromptTemplate template = promptTemplateRegistry.require(
                CryptoMarketReviewPrompts.ANALYST_ID, CryptoMarketReviewPrompts.VERSION_V1);
        List<LlmMessage> messages = List.of(
                new LlmMessage("system", template.systemMessage()),
                new LlmMessage("user", request.input() == null ? "{}" : request.input()));

        return LlmAgentSupport.callLlm(
                        ctx, request.stageId(), AGENT_ID, llmClient, messages, template)
                .flatMap(response -> {
                    try {
                        StrictAgentJson.CryptoAnalystOutput parsed =
                                StrictAgentJson.parseCryptoAnalyst(response.content());
                        String canonical = StrictAgentJson.canonicalJson(response.content());
                        ctx.putAgentOutput(AGENT_ID, canonical);

                        Map<String, String> attrs = new LinkedHashMap<>();
                        attrs.put("agentId", AGENT_ID);
                        attrs.put("bias", parsed.bias());
                        attrs.put("confidence", Double.toString(parsed.confidence()));
                        attrs.put("invalidationsCount", Integer.toString(parsed.invalidations().size()));
                        attrs.put("risksCount", Integer.toString(parsed.risks().size()));
                        attrs.put("structured", WorkflowRunContext.truncate(canonical, 500));
                        ctx.emit(EventType.AGENT_SUCCEEDED, attrs, false);

                        return Mono.just(AgentExecutionResult.ok(AGENT_ID, canonical));
                    } catch (IllegalArgumentException e) {
                        return agentParseFailed(ctx, e);
                    }
                })
                .onErrorResume(error -> {
                    if (error instanceof IllegalArgumentException) {
                        return Mono.error(error);
                    }
                    String msg = error.getMessage() == null ? error.toString() : error.getMessage();
                    ctx.emit(EventType.AGENT_FAILED, Map.of("agentId", AGENT_ID, "error", msg), false);
                    return Mono.error(error);
                });
    }

    private Mono<AgentExecutionResult> agentParseFailed(WorkflowRunContext ctx, IllegalArgumentException e) {
        String msg = AGENT_ID + ": " + e.getMessage();
        ctx.emit(EventType.AGENT_FAILED, Map.of("agentId", AGENT_ID, "error", msg), false);
        return Mono.error(new IllegalArgumentException(msg, e));
    }
}
