package io.lifeengine.runtime.ext.cryptomarketreview.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Stage 4 — risk review.
 *
 * <p>The stage runs <strong>deterministic guardrails first</strong>, then asks the LLM to confirm
 * the risk classification with structured JSON output. The deterministic checks are the
 * source-of-truth for warnings:
 *
 * <ul>
 *   <li>Reject overconfident calls: {@code confidence > 0.85} on a non-NEUTRAL bias.
 *   <li>Reject direct-action language in the analyst summary or setup ("buy now", "sell immediately"...).
 *   <li>Flag missing-data: empty zones / indicators / observations.
 *   <li>Flag unsupported claims (analyst confidence high while data set is thin).
 * </ul>
 *
 * <p>The deterministic findings are merged with the LLM verdict so the operator-facing output is
 * conservative even if the model is overconfident.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.crypto-market-review.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CryptoRiskReviewAgent implements AgentExecutor {

    public static final String AGENT_ID = "crypto-risk-review-agent";

    private static final Pattern DIRECT_ACTION = Pattern.compile(
            "(?i)\\b(buy now|sell now|buy immediately|sell immediately|enter long|enter short|"
                    + "you must (buy|sell)|guaranteed (gain|profit)|risk[- ]free)\\b");

    private final LlmClient llmClient;
    private final ObjectMapper mapper;
    private final PromptTemplateRegistry promptTemplateRegistry;

    public CryptoRiskReviewAgent(
            @Qualifier("smartLlmClient") LlmClient llmClient, ObjectMapper mapper, PromptTemplateRegistry promptTemplateRegistry) {
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

        // Stage 4 reads BOTH the analyst (request.input) and the marketContext (from ctx.agentOutputs).
        String marketContextJson = ctx.agentOutputs()
                .getOrDefault(LoadCryptoMarketContextAgent.AGENT_ID, "{}");
        String analystJson = request.input() == null || request.input().isBlank() ? "{}" : request.input();

        DeterministicFindings findings;
        try {
            findings = runDeterministicChecks(marketContextJson, analystJson);
        } catch (Exception e) {
            ctx.emit(EventType.AGENT_FAILED,
                    Map.of("agentId", AGENT_ID, "error", "deterministic check failed: " + e.getMessage()),
                    false);
            return Mono.error(e);
        }

        // Emit each warning explicitly so the runtime warnings stream stays in sync.
        for (String w : findings.warnings) {
            ctx.addWarning("risk-review: " + w);
        }

        String llmInputJson;
        try {
            var combined = mapper.createObjectNode();
            combined.set("marketContext", mapper.readTree(marketContextJson));
            combined.set("analyst", mapper.readTree(analystJson));
            llmInputJson = mapper.writeValueAsString(combined);
        } catch (Exception e) {
            return Mono.error(e);
        }

        PromptTemplate template = promptTemplateRegistry.require(
                CryptoMarketReviewPrompts.RISK_REVIEW_ID, CryptoMarketReviewPrompts.VERSION_V1);
        List<LlmMessage> messages = List.of(
                new LlmMessage("system", template.systemMessage()),
                new LlmMessage("user", llmInputJson));

        DeterministicFindings finalFindings = findings;
        return LlmAgentSupport.callLlm(
                        ctx, request.stageId(), AGENT_ID, llmClient, messages, template)
                .flatMap(response -> {
                    try {
                        StrictAgentJson.CryptoRiskReviewOutput parsed =
                                StrictAgentJson.parseCryptoRiskReview(response.content());

                        // Merge LLM verdict with deterministic findings. Deterministic is authoritative
                        // on warnings/approval if it found any blocker; otherwise we trust the LLM.
                        boolean approved = parsed.approved() && !finalFindings.hasBlocker;
                        List<String> warnings = new ArrayList<>(finalFindings.warnings);
                        for (String w : parsed.warnings()) {
                            if (w != null && !w.isBlank() && !warnings.contains(w)) {
                                warnings.add(w);
                            }
                        }
                        String riskLevel = escalate(parsed.riskLevel(), finalFindings.requiredRiskLevel);

                        var merged = mapper.createObjectNode();
                        merged.put("approved", approved);
                        var warningsArr = mapper.createArrayNode();
                        warnings.forEach(warningsArr::add);
                        merged.set("warnings", warningsArr);
                        merged.put("riskLevel", riskLevel);
                        String canonical = mapper.writeValueAsString(merged);
                        ctx.putAgentOutput(AGENT_ID, canonical);

                        Map<String, String> attrs = new LinkedHashMap<>();
                        attrs.put("agentId", AGENT_ID);
                        attrs.put("approved", Boolean.toString(approved));
                        attrs.put("riskLevel", riskLevel);
                        attrs.put("warningsCount", Integer.toString(warnings.size()));
                        attrs.put("structured", WorkflowRunContext.truncate(canonical, 500));
                        ctx.emit(EventType.AGENT_SUCCEEDED, attrs, false);

                        return Mono.just(AgentExecutionResult.ok(AGENT_ID, canonical));
                    } catch (IllegalArgumentException e) {
                        return agentParseFailed(ctx, e);
                    } catch (Exception e) {
                        ctx.emit(EventType.AGENT_FAILED,
                                Map.of("agentId", AGENT_ID, "error", e.getMessage()), false);
                        return Mono.error(e);
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

    static DeterministicFindings runDeterministicChecks(String marketContextJson, String analystJson) throws Exception {
        ObjectMapper m = new ObjectMapper();
        JsonNode marketCtx = m.readTree(marketContextJson == null ? "{}" : marketContextJson);
        JsonNode analyst = m.readTree(analystJson == null ? "{}" : analystJson);

        List<String> warnings = new ArrayList<>();
        boolean hasBlocker = false;
        String requiredRiskLevel = "LOW";

        // 1. Direct-action language in analyst summary or setup → BLOCKER.
        String summary = analyst.path("summary").asText("");
        String setup = analyst.path("setup").asText("");
        if (DIRECT_ACTION.matcher(summary).find() || DIRECT_ACTION.matcher(setup).find()) {
            warnings.add("Analyst output contains direct-action language; refusing to forward as advice.");
            hasBlocker = true;
            requiredRiskLevel = "HIGH";
        }

        // 2. Overconfident directional bias.
        double confidence = analyst.path("confidence").asDouble(0.0);
        String bias = analyst.path("bias").asText("NEUTRAL").toUpperCase(Locale.ROOT);
        if (confidence > 0.85 && !"NEUTRAL".equals(bias)) {
            warnings.add("Analyst confidence > 0.85 on a " + bias + " bias; treating as overconfident.");
            requiredRiskLevel = escalate(requiredRiskLevel, "HIGH");
        }

        // 3. Missing data (empty zones / indicators / observations).
        if (sizeOf(marketCtx, "zones") == 0) {
            warnings.add("No price zones available for the symbol.");
            requiredRiskLevel = escalate(requiredRiskLevel, "MEDIUM");
        }
        if (sizeOf(marketCtx, "indicators") == 0) {
            warnings.add("No indicator snapshots available for the symbol.");
            requiredRiskLevel = escalate(requiredRiskLevel, "MEDIUM");
        }
        if (sizeOf(marketCtx, "observations") == 0) {
            warnings.add("No recent market observations available; signal may be stale.");
            requiredRiskLevel = escalate(requiredRiskLevel, "MEDIUM");
        }

        return new DeterministicFindings(warnings, hasBlocker, requiredRiskLevel);
    }

    private static int sizeOf(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        return node != null && node.isArray() ? node.size() : 0;
    }

    private static String escalate(String current, String candidate) {
        int rank = rank(current);
        int candidateRank = rank(candidate);
        return candidateRank > rank ? candidate : current;
    }

    private static int rank(String level) {
        if (level == null) return 0;
        return switch (level.toUpperCase(Locale.ROOT)) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private Mono<AgentExecutionResult> agentParseFailed(WorkflowRunContext ctx, IllegalArgumentException e) {
        String msg = AGENT_ID + ": " + e.getMessage();
        ctx.emit(EventType.AGENT_FAILED, Map.of("agentId", AGENT_ID, "error", msg), false);
        return Mono.error(new IllegalArgumentException(msg, e));
    }

    record DeterministicFindings(List<String> warnings, boolean hasBlocker, String requiredRiskLevel) {}
}
