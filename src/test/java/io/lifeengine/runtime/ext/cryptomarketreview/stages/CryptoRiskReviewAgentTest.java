package io.lifeengine.runtime.ext.cryptomarketreview.stages;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.runtime.ext.cryptomarketreview.stages.CryptoRiskReviewAgent.DeterministicFindings;
import org.junit.jupiter.api.Test;

/**
 * Unit-level checks for the deterministic guardrails inside the risk-review agent. The full
 * LLM-backed path is exercised by {@code CryptoMarketReviewWorkflowTest}.
 */
class CryptoRiskReviewAgentTest {

    @Test
    void deterministicChecks_blockOnDirectActionLanguage() throws Exception {
        String marketCtx = "{\"zones\":[{}],\"indicators\":[{}],\"observations\":[{}]}";
        String analyst = "{\"bias\":\"BULLISH\",\"summary\":\"Conditions look good. Buy now.\","
                + "\"setup\":\"\",\"invalidations\":[],\"confidence\":0.7,\"risks\":[]}";

        DeterministicFindings findings = CryptoRiskReviewAgent.runDeterministicChecks(marketCtx, analyst);
        assertThat(findings.hasBlocker()).isTrue();
        assertThat(findings.requiredRiskLevel()).isEqualTo("HIGH");
        assertThat(findings.warnings())
                .anyMatch(w -> w.toLowerCase().contains("direct-action"));
    }

    @Test
    void deterministicChecks_blockOnOverconfidentDirectionalBias() throws Exception {
        String marketCtx = "{\"zones\":[{}],\"indicators\":[{}],\"observations\":[{}]}";
        String analyst = "{\"bias\":\"BULLISH\",\"summary\":\"Probably going up.\","
                + "\"setup\":\"\",\"invalidations\":[],\"confidence\":0.9,\"risks\":[]}";

        DeterministicFindings findings = CryptoRiskReviewAgent.runDeterministicChecks(marketCtx, analyst);
        assertThat(findings.hasBlocker()).as("overconfidence is a warning, not a hard blocker").isFalse();
        assertThat(findings.requiredRiskLevel()).isEqualTo("HIGH");
        assertThat(findings.warnings())
                .anyMatch(w -> w.toLowerCase().contains("overconfident"));
    }

    @Test
    void deterministicChecks_flagMissingZonesAndIndicators() throws Exception {
        String marketCtx = "{\"zones\":[],\"indicators\":[],\"observations\":[]}";
        String analyst = "{\"bias\":\"NEUTRAL\",\"summary\":\"Range.\",\"setup\":\"\","
                + "\"invalidations\":[],\"confidence\":0.4,\"risks\":[]}";

        DeterministicFindings findings = CryptoRiskReviewAgent.runDeterministicChecks(marketCtx, analyst);
        assertThat(findings.hasBlocker()).isFalse();
        assertThat(findings.requiredRiskLevel()).isEqualTo("MEDIUM");
        assertThat(findings.warnings())
                .anyMatch(w -> w.toLowerCase().contains("zones"))
                .anyMatch(w -> w.toLowerCase().contains("indicator"))
                .anyMatch(w -> w.toLowerCase().contains("observation"));
    }

    @Test
    void deterministicChecks_cleanCaseHasLowRiskAndNoWarnings() throws Exception {
        String marketCtx = "{\"zones\":[{}],\"indicators\":[{}],\"observations\":[{}]}";
        String analyst = "{\"bias\":\"NEUTRAL\",\"summary\":\"Mixed signals.\","
                + "\"setup\":\"Monitor support.\",\"invalidations\":[],\"confidence\":0.4,\"risks\":[]}";

        DeterministicFindings findings = CryptoRiskReviewAgent.runDeterministicChecks(marketCtx, analyst);
        assertThat(findings.hasBlocker()).isFalse();
        assertThat(findings.requiredRiskLevel()).isEqualTo("LOW");
        assertThat(findings.warnings()).isEmpty();
    }
}
