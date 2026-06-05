package io.lifeengine.runtime.ext.cryptomarketreview.stages;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Stage 5 — operator-facing summary. Composes the previous stages' outputs (analyst + risk
 * review) into a single short response. The system prompt enforces that this is market analysis,
 * not personalized financial advice.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.crypto-market-review.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CryptoFinalSummaryAgent implements AgentExecutor {

    public static final String AGENT_ID = "crypto-final-summary-agent";
    static final String DISCLAIMER = "This is market analysis, not financial advice.";

    private final LlmClient llmClient;
    private final ObjectMapper mapper;
    private final PromptTemplateRegistry promptTemplateRegistry;

    public CryptoFinalSummaryAgent(
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

        // Stage 5 reads the analyst and risk-review outputs from the context.
        String analyst = ctx.agentOutputs().getOrDefault(CryptoMarketAnalystAgent.AGENT_ID, "{}");
        String riskReview = request.input() == null || request.input().isBlank() ? "{}" : request.input();

        String userInput;
        try {
            var combined = mapper.createObjectNode();
            combined.set("analyst", mapper.readTree(analyst));
            combined.set("riskReview", mapper.readTree(riskReview));
            userInput = mapper.writeValueAsString(combined);
        } catch (Exception e) {
            return Mono.error(e);
        }

        PromptTemplate template = promptTemplateRegistry.require(
                CryptoMarketReviewPrompts.FINAL_SUMMARY_ID, CryptoMarketReviewPrompts.VERSION_V1);
        List<LlmMessage> messages = List.of(
                new LlmMessage("system", template.systemMessage()),
                new LlmMessage("user", userInput));

        return LlmAgentSupport.callLlm(
                        ctx, request.stageId(), AGENT_ID, llmClient, messages, template)
                .flatMap(response -> {
                    String raw = response.content();
                    String canonical;
                    boolean fallbackUsed = false;
                    String parseError = null;
                    String responsePreview;
                    try {
                        StrictAgentJson.CryptoFinalSummaryOutput parsed =
                                StrictAgentJson.parseCryptoFinalSummary(raw);
                        canonical = StrictAgentJson.canonicalJson(raw);
                        responsePreview = parsed.response();
                    } catch (IllegalArgumentException e) {
                        // The model emitted prose (e.g. "Warning: ..."). Wrap it in a
                        // deterministic {"response": "..."} envelope so the workflow still
                        // reaches RUN_SUCCEEDED. The disclaimer is appended if missing so
                        // the operator-facing contract is preserved regardless of model
                        // output strictness. Observability: AGENT_SUCCEEDED carries
                        // fallback=true and parseError so this branch is visible in events.
                        fallbackUsed = true;
                        parseError = e.getMessage();
                        canonical = buildFinalSummaryFallback(mapper, raw);
                        responsePreview = extractResponseText(canonical);
                    }
                    ctx.putAgentOutput(AGENT_ID, canonical);

                    Map<String, String> attrs = new LinkedHashMap<>();
                    attrs.put("agentId", AGENT_ID);
                    attrs.put("responsePreview", WorkflowRunContext.truncate(responsePreview, 500));
                    if (fallbackUsed) {
                        attrs.put("fallback", "true");
                        attrs.put(
                                "parseError",
                                WorkflowRunContext.truncate(
                                        parseError == null ? "unknown" : parseError, 240));
                    }
                    ctx.emit(EventType.AGENT_SUCCEEDED, attrs, false);

                    return Mono.just(AgentExecutionResult.ok(AGENT_ID, canonical));
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

    /**
     * Build a deterministic {@code {"response": "..."}} envelope from raw model prose. Used as
     * fallback when the model violates the strict-JSON contract for the final-summary stage.
     * Always preserves the operator-facing disclaimer — appends it iff not already present.
     *
     * <p>Visible to tests; not part of the public API of this agent.
     */
    static String buildFinalSummaryFallback(ObjectMapper mapper, String rawModelOutput) {
        String cleaned = rawModelOutput == null ? "" : rawModelOutput.trim();
        if (cleaned.isEmpty()) {
            cleaned = "(no model output)";
        }
        if (!cleaned.toLowerCase(Locale.ROOT).contains(DISCLAIMER.toLowerCase(Locale.ROOT))) {
            String separator;
            if (cleaned.endsWith(".") || cleaned.endsWith("!") || cleaned.endsWith("?")) {
                separator = " ";
            } else {
                separator = ". ";
            }
            cleaned = cleaned + separator + DISCLAIMER;
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("response", cleaned);
        return out.toString();
    }

    private String extractResponseText(String canonicalJson) {
        try {
            return mapper.readTree(canonicalJson).path("response").asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
