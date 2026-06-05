package io.lifeengine.runtime.ext.cryptomarketreview.stages;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Unit-level checks for the deterministic fallback inside the final-summary agent. The full
 * LLM-backed happy path is exercised by {@code CryptoMarketReviewWorkflowTest}.
 *
 * <p>The fallback fires when the model violates the {@code {"response": "..."}} contract
 * (a real-world failure mode for small instruct models like Qwen2.5-Coder-3B). It must
 * always:
 * <ul>
 *   <li>produce a valid JSON object with a single {@code response} string field,
 *   <li>include the operator-facing disclaimer ("This is market analysis, not financial advice."),
 *   <li>never duplicate the disclaimer if it is already present in the model output.
 * </ul>
 */
class CryptoFinalSummaryAgentFallbackTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void fallback_wrapsRawProse_andAppendsDisclaimer_whenMissing() throws Exception {
        String raw = "Warning: BTCUSDT is trading inside a defined range; monitor 70k breakout.";

        String canonical = CryptoFinalSummaryAgent.buildFinalSummaryFallback(JSON, raw);

        JsonNode node = JSON.readTree(canonical);
        assertThat(node.isObject()).as("fallback is a JSON object").isTrue();
        assertThat(node.fieldNames()).toIterable().containsExactly("response");
        assertThat(node.get("response").asText())
                .startsWith("Warning: BTCUSDT")
                .endsWith(CryptoFinalSummaryAgent.DISCLAIMER);
    }

    @Test
    void fallback_doesNotDuplicateDisclaimer_whenAlreadyPresent() throws Exception {
        String raw = "Warning: regime change risk is high. "
                + CryptoFinalSummaryAgent.DISCLAIMER;

        String canonical = CryptoFinalSummaryAgent.buildFinalSummaryFallback(JSON, raw);

        String response = JSON.readTree(canonical).get("response").asText();
        // Exactly one occurrence of the disclaimer (case-insensitive contains check).
        long occurrences = countOccurrences(response.toLowerCase(),
                CryptoFinalSummaryAgent.DISCLAIMER.toLowerCase());
        assertThat(occurrences).as("disclaimer appears exactly once").isEqualTo(1L);
    }

    @Test
    void fallback_isCaseInsensitive_whenDetectingExistingDisclaimer() throws Exception {
        String raw = "Conditions are mixed. THIS IS MARKET ANALYSIS, NOT FINANCIAL ADVICE.";

        String canonical = CryptoFinalSummaryAgent.buildFinalSummaryFallback(JSON, raw);
        String response = JSON.readTree(canonical).get("response").asText();

        // Original (uppercase) variant survives unchanged; no canonical-case duplicate appended.
        assertThat(response).contains("THIS IS MARKET ANALYSIS, NOT FINANCIAL ADVICE.");
        assertThat(response).doesNotContain(
                "advice. " + CryptoFinalSummaryAgent.DISCLAIMER);
    }

    @Test
    void fallback_handlesEmptyAndNullRawOutput_gracefully() throws Exception {
        for (String raw : new String[] {null, "", "   "}) {
            String canonical = CryptoFinalSummaryAgent.buildFinalSummaryFallback(JSON, raw);
            JsonNode node = JSON.readTree(canonical);
            String response = node.get("response").asText();
            assertThat(response)
                    .as("empty raw input still produces a usable response (raw=%s)", raw)
                    .contains("(no model output)")
                    .endsWith(CryptoFinalSummaryAgent.DISCLAIMER);
        }
    }

    @Test
    void fallback_appendsDisclaimerWithProperSeparator_whenInputLacksTerminalPunctuation() throws Exception {
        String raw = "Range trade hypothesis confirmed";

        String canonical = CryptoFinalSummaryAgent.buildFinalSummaryFallback(JSON, raw);
        String response = JSON.readTree(canonical).get("response").asText();

        // No double-period; cleaned source had no terminal punctuation, fallback inserted ". ".
        assertThat(response).isEqualTo(
                "Range trade hypothesis confirmed. " + CryptoFinalSummaryAgent.DISCLAIMER);
    }

    @Test
    void fallback_doesNotInsertDoublePeriod_whenInputAlreadyEndsWithPunctuation() throws Exception {
        String raw = "Bias unchanged.";

        String canonical = CryptoFinalSummaryAgent.buildFinalSummaryFallback(JSON, raw);
        String response = JSON.readTree(canonical).get("response").asText();

        assertThat(response).isEqualTo(
                "Bias unchanged. " + CryptoFinalSummaryAgent.DISCLAIMER);
        assertThat(response).doesNotContain("..");
    }

    @Test
    void fallback_outputIsValidJson_andContainsOnlyResponseField() throws Exception {
        String raw = "Anything: with \"embedded\" quotes and \\backslashes\\";

        String canonical = CryptoFinalSummaryAgent.buildFinalSummaryFallback(JSON, raw);

        JsonNode node = JSON.readTree(canonical); // parses cleanly => quoting handled correctly
        assertThat(node.isObject()).isTrue();
        assertThat(node.size()).as("only a single field").isEqualTo(1);
        assertThat(node.has("response")).isTrue();
        assertThat(node.get("response").asText())
                .contains("\"embedded\"")
                .contains("\\backslashes\\")
                .endsWith(CryptoFinalSummaryAgent.DISCLAIMER);
    }

    private static long countOccurrences(String haystack, String needle) {
        if (needle == null || needle.isEmpty()) {
            return 0L;
        }
        long count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
