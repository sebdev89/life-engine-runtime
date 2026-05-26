package io.lifeengine.runtime.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StrictAgentJsonCryptoTest {

    @Test
    void parseCryptoIntent_acceptsCanonicalShape() {
        var out = StrictAgentJson.parseCryptoIntent(
                "{\"intent\":\"MARKET_REVIEW\",\"symbol\":\"BTCUSDT\",\"timeframe\":\"1h\"}");
        assertThat(out.intent()).isEqualTo("MARKET_REVIEW");
        assertThat(out.symbol()).isEqualTo("BTCUSDT");
        assertThat(out.timeframe()).isEqualTo("1h");
    }

    @Test
    void parseCryptoAnalyst_rejectsInvalidBias() {
        String raw = "{\"bias\":\"PARTY\",\"summary\":\"x\",\"setup\":\"y\","
                + "\"invalidations\":[],\"confidence\":0.5,\"risks\":[]}";
        assertThatThrownBy(() -> StrictAgentJson.parseCryptoAnalyst(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bias");
    }

    @Test
    void parseCryptoAnalyst_rejectsConfidenceOutOfRange() {
        String raw = "{\"bias\":\"NEUTRAL\",\"summary\":\"x\",\"setup\":\"y\","
                + "\"invalidations\":[],\"confidence\":1.5,\"risks\":[]}";
        assertThatThrownBy(() -> StrictAgentJson.parseCryptoAnalyst(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    void parseCryptoAnalyst_acceptsHappyShape() {
        String raw = "{\"bias\":\"BULLISH\",\"summary\":\"a\",\"setup\":\"b\","
                + "\"invalidations\":[\"break below 60k\"],\"confidence\":0.6,"
                + "\"risks\":[\"liquidity\",\"macro\"]}";
        var out = StrictAgentJson.parseCryptoAnalyst(raw);
        assertThat(out.bias()).isEqualTo("BULLISH");
        assertThat(out.invalidations()).hasSize(1);
        assertThat(out.risks()).hasSize(2);
        assertThat(out.confidence()).isEqualTo(0.6);
    }

    @Test
    void parseCryptoRiskReview_rejectsMissingApproved() {
        String raw = "{\"warnings\":[],\"riskLevel\":\"LOW\"}";
        assertThatThrownBy(() -> StrictAgentJson.parseCryptoRiskReview(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approved");
    }

    @Test
    void parseCryptoRiskReview_rejectsInvalidRiskLevel() {
        String raw = "{\"approved\":true,\"warnings\":[],\"riskLevel\":\"EXTREME\"}";
        assertThatThrownBy(() -> StrictAgentJson.parseCryptoRiskReview(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("riskLevel");
    }

    @Test
    void parseCryptoFinalSummary_requiresResponse() {
        assertThatThrownBy(() -> StrictAgentJson.parseCryptoFinalSummary("{}"))
                .isInstanceOf(IllegalArgumentException.class);

        var out = StrictAgentJson.parseCryptoFinalSummary(
                "{\"response\":\"summary text. This is market analysis, not financial advice.\"}");
        assertThat(out.response()).contains("market analysis");
    }
}
