package io.lifeengine.runtime.ext.cryptomarketreview;

import io.lifeengine.runtime.ext.cryptomarketreview.stages.CryptoFinalSummaryAgent;
import io.lifeengine.runtime.ext.cryptomarketreview.stages.CryptoMarketAnalystAgent;
import io.lifeengine.runtime.ext.cryptomarketreview.stages.CryptoRiskReviewAgent;
import io.lifeengine.runtime.ext.cryptomarketreview.stages.LoadCryptoMarketContextAgent;
import io.lifeengine.runtime.ext.cryptomarketreview.stages.ParseCryptoIntentAgent;
import io.lifeengine.runtime.extension.RuntimeModule;
import io.lifeengine.runtime.extension.RuntimeRegistry;
import io.lifeengine.runtime.workflow.WorkflowDefinition;
import io.lifeengine.runtime.workflow.WorkflowStage;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registers the {@code crypto.market-review.v1} workflow with a five-agent pipeline:
 *
 * <ol>
 *   <li>{@link ParseCryptoIntentAgent} — deterministic intent parsing (no LLM).
 *   <li>{@link LoadCryptoMarketContextAgent} — fan-out to seven cryptobot HTTP tools (no LLM).
 *   <li>{@link CryptoMarketAnalystAgent} — LLM-backed analyst (strict JSON).
 *   <li>{@link CryptoRiskReviewAgent} — deterministic guardrails + LLM verdict (strict JSON).
 *   <li>{@link CryptoFinalSummaryAgent} — LLM-backed operator-facing summary (strict JSON).
 * </ol>
 *
 * <p>The previous deterministic 3-tool pipeline ({@code market-snapshot} → {@code signal-engine} →
 * {@code review-synth}) has been retired. The seven cryptobot HTTP tools are invoked by
 * {@code LoadCryptoMarketContextAgent} and therefore do not appear as top-level workflow stages —
 * they emit {@code TOOL_STARTED}/{@code TOOL_SUCCEEDED} events nested inside stage 2's
 * {@code STAGE_STARTED} window.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.crypto-market-review.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CryptoMarketReviewModule implements RuntimeModule {

    public static final String MODULE_ID = "crypto-market-review";
    public static final String WORKFLOW_ID = "crypto.market-review.v1";
    public static final String INPUT_CONTRACT = "cryptobot.market-review-input.v1";
    public static final String OUTPUT_CONTRACT = "cryptobot.market-review-output.v1";

    public static final String STAGE_PARSE_INTENT = "parse-intent";
    public static final String STAGE_LOAD_CONTEXT = "load-market-context";
    public static final String STAGE_ANALYSE = "market-analyst";
    public static final String STAGE_RISK_REVIEW = "risk-review";
    public static final String STAGE_FINAL_SUMMARY = "final-summary";

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public void register(RuntimeRegistry registry) {
        registry.registerPromptTemplate(CryptoMarketReviewPrompts.analyst());
        registry.registerPromptTemplate(CryptoMarketReviewPrompts.riskReview());
        registry.registerPromptTemplate(CryptoMarketReviewPrompts.finalSummary());

        registry.registerWorkflow(
                new WorkflowDefinition(
                        WORKFLOW_ID,
                        INPUT_CONTRACT,
                        OUTPUT_CONTRACT,
                        List.of(
                                new WorkflowStage(
                                        STAGE_PARSE_INTENT,
                                        1,
                                        WorkflowStage.StageKind.AGENT,
                                        ParseCryptoIntentAgent.AGENT_ID),
                                new WorkflowStage(
                                        STAGE_LOAD_CONTEXT,
                                        2,
                                        WorkflowStage.StageKind.AGENT,
                                        LoadCryptoMarketContextAgent.AGENT_ID),
                                new WorkflowStage(
                                        STAGE_ANALYSE,
                                        3,
                                        WorkflowStage.StageKind.AGENT,
                                        CryptoMarketAnalystAgent.AGENT_ID),
                                new WorkflowStage(
                                        STAGE_RISK_REVIEW,
                                        4,
                                        WorkflowStage.StageKind.AGENT,
                                        CryptoRiskReviewAgent.AGENT_ID),
                                new WorkflowStage(
                                        STAGE_FINAL_SUMMARY,
                                        5,
                                        WorkflowStage.StageKind.AGENT,
                                        CryptoFinalSummaryAgent.AGENT_ID)),
                        Duration.ofMinutes(3),
                        "Crypto market review (intent → load-context → analyst → risk-review → final-summary)"));
    }
}
