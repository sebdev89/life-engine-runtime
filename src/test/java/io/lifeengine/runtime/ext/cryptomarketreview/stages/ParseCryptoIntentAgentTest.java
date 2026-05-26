package io.lifeengine.runtime.ext.cryptomarketreview.stages;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ParseCryptoIntentAgentTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ParseCryptoIntentAgent agent = new ParseCryptoIntentAgent(mapper);

    @Test
    void parse_acceptsJsonInput() throws Exception {
        ObjectNode out = agent.parse("{\"symbol\":\"btcusdt\",\"timeframe\":\"4h\"}");
        assertThat(out.get("symbol").asText()).isEqualTo("BTCUSDT");
        assertThat(out.get("timeframe").asText()).isEqualTo("4h");
        assertThat(out.get("intent").asText()).isEqualTo("MARKET_REVIEW");
        assertThat(out.get("source").asText()).isEqualTo("json");
    }

    @Test
    void parse_extractsSymbolFromFreeText() throws Exception {
        ObjectNode out = agent.parse("Review BTCUSDT and tell me if there is a signal.");
        assertThat(out.get("symbol").asText()).isEqualTo("BTCUSDT");
        assertThat(out.get("timeframe").asText()).isEqualTo("1h");
        assertThat(out.get("source").asText()).isEqualTo("free-text");
    }

    @Test
    void parse_extractsTimeframeFromFreeText() throws Exception {
        ObjectNode out = agent.parse("Quick review on ETHUSDT 15m please");
        assertThat(out.get("symbol").asText()).isEqualTo("ETHUSDT");
        assertThat(out.get("timeframe").asText()).isEqualTo("15m");
    }

    @Test
    void parse_failsWhenNoSymbolPresent() {
        try {
            agent.parse("review please");
            assert false : "expected exception";
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("symbol");
        }
    }

    @Test
    void extractSymbol_prefersTokensWithQuoteSuffix() {
        assertThat(ParseCryptoIntentAgent.extractSymbol("BTC vs USDT versus ETHUSDT today"))
                .isEqualTo("ETHUSDT");
    }
}
