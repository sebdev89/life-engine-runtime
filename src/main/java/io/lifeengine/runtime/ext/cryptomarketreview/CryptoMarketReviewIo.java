package io.lifeengine.runtime.ext.cryptomarketreview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;

/**
 * Workflow-input/output codec for {@link CryptoMarketReviewModule#WORKFLOW_ID}. Centralized so that
 * each stage parses the canonical shape the same way; tests use these helpers too.
 */
public final class CryptoMarketReviewIo {

    private CryptoMarketReviewIo() {}

    /** Canonical JSON input shape posted by cryptobot-service as {@code StartRunRequest.input}. */
    public record Input(
            String contractId, String symbol, String snapshotSource, String observedAt, String marketReviewId) {

        public static Input fromSymbol(String symbol) {
            return new Input(
                    CryptoMarketReviewModule.INPUT_CONTRACT,
                    symbol,
                    "deterministic-local",
                    Instant.now().toString(),
                    "");
        }
    }

    public static Input readInput(ObjectMapper mapper, String raw) throws JsonProcessingException {
        if (raw == null || raw.isBlank()) {
            return new Input(
                    CryptoMarketReviewModule.INPUT_CONTRACT, "UNKNOWN", "deterministic-local", Instant.now().toString(), "");
        }
        try {
            JsonNode root = mapper.readTree(raw);
            if (root.isTextual()) {
                return new Input(
                        CryptoMarketReviewModule.INPUT_CONTRACT,
                        root.asText(),
                        "deterministic-local",
                        Instant.now().toString(),
                        "");
            }
            String symbol = root.path("symbol").asText("UNKNOWN");
            String source = root.path("snapshotSource").asText("deterministic-local");
            String observedAt = root.path("observedAt").asText(Instant.now().toString());
            String mrId = root.path("marketReviewId").asText("");
            return new Input(CryptoMarketReviewModule.INPUT_CONTRACT, symbol, source, observedAt, mrId);
        } catch (JsonProcessingException ex) {
            return new Input(
                    CryptoMarketReviewModule.INPUT_CONTRACT,
                    raw.trim(),
                    "deterministic-local",
                    Instant.now().toString(),
                    "");
        }
    }
}
