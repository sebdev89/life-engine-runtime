package io.lifeengine.runtime.ext.cryptomarketreview;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.api.RunResponse;
import io.lifeengine.runtime.api.RuntimeEventResponse;
import io.lifeengine.runtime.api.WorkflowListView;
import io.lifeengine.runtime.app.RuntimeApplication;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.ext.cryptomarketreview.stages.CryptoFinalSummaryAgent;
import io.lifeengine.runtime.ext.cryptomarketreview.stages.CryptoMarketAnalystAgent;
import io.lifeengine.runtime.ext.cryptomarketreview.stages.CryptoRiskReviewAgent;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Happy-path end-to-end test for {@code crypto.market-review.v1} (the 5-agent pipeline).
 * Stands up two {@link MockWebServer} instances — one for the LLM, one for cryptobot-service —
 * so neither external dependency is required to run the test.
 *
 * <p>Asserts:
 * <ul>
 *   <li>Workflow auto-registration at boot.
 *   <li>RUN_STARTED → (5 × stage envelopes) → RUN_SUCCEEDED event ordering.
 *   <li>Stage-2 emits seven nested TOOL_STARTED/TOOL_SUCCEEDED events (one per cryptobot tool).
 *   <li>Stage 3/4/5 each emit LLM_CALL_STARTED + LLM_CALL_SUCCEEDED with model/usage/latency.
 *   <li>Token usage telemetry is present on every LLM call.
 * </ul>
 */
@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class CryptoMarketReviewWorkflowTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private static MockWebServer mockLlm;
    private static MockWebServer mockCryptobot;

    @Autowired private WebTestClient webTestClient;

    @BeforeAll
    static void startMocks() throws IOException {
        mockLlm = new MockWebServer();
        mockLlm.start();
        mockCryptobot = new MockWebServer();
        mockCryptobot.setDispatcher(cryptobotDispatcher());
        mockCryptobot.start();
    }

    @AfterAll
    static void stopMocks() throws IOException {
        if (mockLlm != null) {
            mockLlm.shutdown();
        }
        if (mockCryptobot != null) {
            mockCryptobot.shutdown();
        }
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        String mockUrl = mockLlm.url("/").toString().replaceAll("/$", "");
        registry.add("runtime.llm.base-url", () -> mockUrl);
        registry.add("runtime.llm.model", () -> "test-model");
        registry.add("runtime.llm.api-key", () -> "test-key");
        // Fase 5: crypto agents use smartLlmClient — point it at the same mock.
        registry.add("runtime.llm.smart.base-url", () -> mockUrl);
        registry.add(
                "lifeengine.runtime.ext.crypto-market-review.cryptobot.base-url",
                () -> mockCryptobot.url("/").toString().replaceAll("/$", ""));
        registry.add("lifeengine.runtime.ext.crypto-market-review.cryptobot.timeout", () -> "5s");
    }

    @Test
    void workflowIsRegisteredOnBoot() {
        List<WorkflowListView> workflows = webTestClient.get().uri("/api/runtime/workflows").exchange()
                .expectStatus().isOk()
                .expectBodyList(WorkflowListView.class).returnResult().getResponseBody();

        Assertions.assertThat(workflows).isNotNull()
                .anyMatch(w -> CryptoMarketReviewModule.WORKFLOW_ID.equals(w.workflowId()));
    }

    @Test
    void finalSummaryFallback_onProseResponse_stillReachesRunSucceeded() throws Exception {
        enqueueLlm(analystResponseJson());
        enqueueLlm(riskReviewResponseJson());
        // Final-summary stage receives free-text prose (the real-world Qwen2.5-Coder-3B
        // failure mode that surfaced after the analyst context-window fix). The deterministic
        // fallback in CryptoFinalSummaryAgent must wrap the prose into {"response": "..."}
        // and the run must reach SUCCEEDED rather than FAILED.
        String prose = "Warning: BTCUSDT trades inside a defined range; monitor for breakout.";
        enqueueLlm(prose);

        UUID runId = startMarketReviewRun();
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<RuntimeEventResponse> events = collectEvents(runId);
        List<String> types = events.stream().map(RuntimeEventResponse::type).toList();

        Assertions.assertThat(types).contains("RUN_STARTED", "RUN_SUCCEEDED");
        long stageSucceeded = types.stream().filter("STAGE_SUCCEEDED"::equals).count();
        Assertions.assertThat(stageSucceeded)
                .as("all 5 stages SUCCEEDED even though final-summary LLM emitted prose")
                .isEqualTo(5);

        // The agent emits AGENT_SUCCEEDED with fallback=true so operators can see that the
        // strict-JSON contract was rescued by the deterministic envelope.
        RuntimeEventResponse finalSummarySucceeded = events.stream()
                .filter(e -> "AGENT_SUCCEEDED".equals(e.type()))
                .filter(e -> "crypto-final-summary-agent".equals(e.agentId()))
                .findFirst()
                .orElseThrow();
        Assertions.assertThat(finalSummarySucceeded.payload().get("fallback")).isEqualTo("true");
        Assertions.assertThat(finalSummarySucceeded.payload().get("parseError"))
                .as("parseError telemetry recorded for observability")
                .isNotNull();

        // The persisted final-summary stage output must be valid JSON containing the original
        // prose plus the appended disclaimer.
        com.fasterxml.jackson.databind.JsonNode detail = webTestClient.get()
                .uri("/api/runtime/runs/{runId}", runId)
                .accept(MediaType.APPLICATION_JSON)
                .exchange().expectStatus().isOk()
                .expectBody(com.fasterxml.jackson.databind.JsonNode.class)
                .returnResult().getResponseBody();
        com.fasterxml.jackson.databind.JsonNode finalStage = null;
        for (com.fasterxml.jackson.databind.JsonNode stage : detail.get("agentStages")) {
            if ("final-summary".equals(stage.path("stageId").asText())) {
                finalStage = stage;
                break;
            }
        }
        Assertions.assertThat(finalStage).as("final-summary stage present").isNotNull();
        Assertions.assertThat(finalStage.get("status").asText()).isEqualTo("SUCCEEDED");
        com.fasterxml.jackson.databind.JsonNode parsedOutput = JSON.readTree(finalStage.get("output").asText());
        Assertions.assertThat(parsedOutput.get("response").asText())
                .startsWith("Warning: BTCUSDT")
                .endsWith("This is market analysis, not financial advice.");
    }

    @Test
    void analystSeesLeanMarketContext_noPersistenceMetadata_underTokenBudget() throws Exception {
        enqueueLlm(analystResponseJson());
        enqueueLlm(riskReviewResponseJson());
        enqueueLlm(finalSummaryResponseJson());

        UUID runId = startMarketReviewRun();
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        // The first LLM request the workflow issues is the analyst's; capture it from the
        // mock LLM and inspect the user-message that the analyst actually saw.
        RecordedRequest analystRequest = mockLlm.takeRequest(2, TimeUnit.SECONDS);
        Assertions.assertThat(analystRequest).as("analyst LLM request reached mockLlm").isNotNull();
        com.fasterxml.jackson.databind.JsonNode body =
                JSON.readTree(analystRequest.getBody().readUtf8());
        com.fasterxml.jackson.databind.JsonNode messages = body.get("messages");
        Assertions.assertThat(messages).isNotNull();
        Assertions.assertThat(messages.size()).isEqualTo(2);
        String userContent = messages.get(1).get("content").asText();
        com.fasterxml.jackson.databind.JsonNode marketContext = JSON.readTree(userContent);

        // Lean shape: top-level keys only (no extras).
        Assertions.assertThat((Iterable<String>) marketContext::fieldNames)
                .as("marketContext top-level fields")
                .containsExactlyInAnyOrder(
                        "symbol", "timeframe", "price", "ticker24h",
                        "watchlist", "zones", "observations", "journal", "indicators");

        // price is a flat number, not an object with timestamps/source.
        Assertions.assertThat(marketContext.get("price").isNumber()).isTrue();
        Assertions.assertThat(marketContext.get("ticker24h").has("priceChangePct24h")).isTrue();
        Assertions.assertThat(marketContext.get("ticker24h").has("source")).isFalse();
        Assertions.assertThat(marketContext.get("ticker24h").has("observedAt")).isFalse();

        // Watchlist is filtered to the current symbol with minimal fields only.
        com.fasterxml.jackson.databind.JsonNode watchlist = marketContext.get("watchlist");
        Assertions.assertThat(watchlist.size()).as("watchlist filtered to current symbol").isEqualTo(1);
        Assertions.assertThat(watchlist.get(0).get("symbol").asText()).isEqualTo("BTCUSDT");
        Assertions.assertThat(watchlist.get(0).has("sectorTheme")).isFalse();
        Assertions.assertThat(watchlist.get(0).has("priority")).isFalse();
        Assertions.assertThat(watchlist.get(0).has("notes")).isFalse();

        // The persistence-metadata fields the projection must strip from EVERY array entry.
        String[] noiseFields = {
            "id", "createdAt", "updatedAt", "validFrom", "validUntil",
            "valueJson", "tags", "linkedWatchlistId", "entryTime", "computedAt",
            "spreadBps", "volumeQuote24h", "venue"
        };
        String[] arraySections = {"watchlist", "zones", "observations", "journal", "indicators"};
        for (String section : arraySections) {
            com.fasterxml.jackson.databind.JsonNode arr = marketContext.get(section);
            Assertions.assertThat(arr.isArray()).as("%s is an array", section).isTrue();
            for (int i = 0; i < arr.size(); i++) {
                com.fasterxml.jackson.databind.JsonNode entry = arr.get(i);
                for (String noise : noiseFields) {
                    Assertions.assertThat(entry.has(noise))
                            .as("%s[%d] must NOT contain noise field %s", section, i, noise)
                            .isFalse();
                }
            }
        }

        // Hard byte-budget: ensure the lean prompt+output sum stays well under the 2048-token
        // ceiling at the observed ~2.5 chars/token rate, without trusting any specific tokenizer.
        Assertions.assertThat(userContent.length())
                .as("lean marketContext stays small (<=1500 chars => ~600 tokens)")
                .isLessThanOrEqualTo(1500);
    }

    @Test
    void cryptoMarketReviewWorkflow_runsAllFiveStages_andEmitsFullSequence() {
        enqueueLlm(analystResponseJson());
        enqueueLlm(riskReviewResponseJson());
        enqueueLlm(finalSummaryResponseJson());

        UUID runId = startMarketReviewRun();
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<RuntimeEventResponse> events = collectEvents(runId);
        List<String> types = events.stream().map(RuntimeEventResponse::type).toList();

        // RUN_STARTED + 5 STAGE_STARTED + RUN_SUCCEEDED minimum.
        Assertions.assertThat(types).contains("RUN_STARTED", "RUN_SUCCEEDED");
        long stageStartedCount = types.stream().filter("STAGE_STARTED"::equals).count();
        long stageSucceededCount = types.stream().filter("STAGE_SUCCEEDED"::equals).count();
        Assertions.assertThat(stageStartedCount).as("STAGE_STARTED count").isEqualTo(5);
        Assertions.assertThat(stageSucceededCount).as("STAGE_SUCCEEDED count").isEqualTo(5);

        // Each agent emits its own AGENT_STARTED/AGENT_SUCCEEDED.
        long agentSucceeded = types.stream().filter("AGENT_SUCCEEDED"::equals).count();
        Assertions.assertThat(agentSucceeded).as("AGENT_SUCCEEDED count").isEqualTo(5);

        // Stage 2 fans out to the seven cryptobot tools.
        long toolStarted = types.stream().filter("TOOL_STARTED"::equals).count();
        long toolSucceeded = types.stream().filter("TOOL_SUCCEEDED"::equals).count();
        Assertions.assertThat(toolStarted).as("TOOL_STARTED count").isEqualTo(7);
        Assertions.assertThat(toolSucceeded).as("TOOL_SUCCEEDED count").isEqualTo(7);

        // Three LLM stages each call the LLM once.
        long llmStarted = types.stream().filter("LLM_CALL_STARTED"::equals).count();
        long llmSucceeded = types.stream().filter("LLM_CALL_SUCCEEDED"::equals).count();
        Assertions.assertThat(llmStarted).as("LLM_CALL_STARTED count").isEqualTo(3);
        Assertions.assertThat(llmSucceeded).as("LLM_CALL_SUCCEEDED count").isEqualTo(3);

        // Stage ids in order match the workflow definition.
        List<String> stageIds = events.stream()
                .filter(e -> "STAGE_STARTED".equals(e.type()))
                .map(RuntimeEventResponse::stageId).toList();
        Assertions.assertThat(stageIds).containsExactly(
                CryptoMarketReviewModule.STAGE_PARSE_INTENT,
                CryptoMarketReviewModule.STAGE_LOAD_CONTEXT,
                CryptoMarketReviewModule.STAGE_ANALYSE,
                CryptoMarketReviewModule.STAGE_RISK_REVIEW,
                CryptoMarketReviewModule.STAGE_FINAL_SUMMARY);

        // Every LLM_CALL_SUCCEEDED event carries `model`, `latencyMs`, and `usage`.
        // Fase 5: crypto agents use smartLlmClient (test-smart-model), not the default.
        List<RuntimeEventResponse> llmSuccess = events.stream()
                .filter(e -> "LLM_CALL_SUCCEEDED".equals(e.type())).toList();
        for (RuntimeEventResponse ev : llmSuccess) {
            Assertions.assertThat(ev.payload().get("model")).isEqualTo("test-smart-model");
            Assertions.assertThat(ev.payload().get("latencyMs")).matches("\\d+");
            Assertions.assertThat(ev.payload().get("usage")).contains("total_tokens");
        }

        // The three crypto LLM agents must tag every LLM_CALL_STARTED / LLM_CALL_SUCCEEDED
        // with the prompt template id and version drawn from PromptTemplateRegistry.
        Map<String, String> expectedTemplateIdByAgent = Map.of(
                CryptoMarketAnalystAgent.AGENT_ID, CryptoMarketReviewPrompts.ANALYST_ID,
                CryptoRiskReviewAgent.AGENT_ID, CryptoMarketReviewPrompts.RISK_REVIEW_ID,
                CryptoFinalSummaryAgent.AGENT_ID, CryptoMarketReviewPrompts.FINAL_SUMMARY_ID);
        List<RuntimeEventResponse> llmEnvelopes = events.stream()
                .filter(e -> "LLM_CALL_STARTED".equals(e.type()) || "LLM_CALL_SUCCEEDED".equals(e.type()))
                .toList();
        Assertions.assertThat(llmEnvelopes).hasSize(6); // 3 STARTED + 3 SUCCEEDED
        for (RuntimeEventResponse ev : llmEnvelopes) {
            String expectedId = expectedTemplateIdByAgent.get(ev.agentId());
            Assertions.assertThat(expectedId).as("known crypto agent on event %s", ev.agentId())
                    .isNotNull();
            Assertions.assertThat(ev.payload().get("promptTemplateId"))
                    .as("promptTemplateId on %s for agent %s", ev.type(), ev.agentId())
                    .isEqualTo(expectedId);
            Assertions.assertThat(ev.payload().get("promptTemplateVersion"))
                    .as("promptTemplateVersion on %s for agent %s", ev.type(), ev.agentId())
                    .isEqualTo(CryptoMarketReviewPrompts.VERSION_V1);
        }

        // Full prompt text must not be exposed on events: no payload key carries the raw
        // system-message body. Each marker below is taken from the tail of one of the three
        // system prompts and falls well outside the 240-char `promptPreview` window.
        String analystTailMarker = "Keep summary objective; no hyperbole.";
        String riskTailMarker = "If unsure, mark riskLevel=HIGH.";
        String summaryTailMarker = "Always end with the disclaimer string above.";
        for (RuntimeEventResponse ev : events) {
            for (Map.Entry<String, String> entry : ev.payload().entrySet()) {
                String value = entry.getValue() == null ? "" : entry.getValue();
                Assertions.assertThat(value)
                        .as("event %s payload key %s leaks analyst prompt body", ev.type(), entry.getKey())
                        .doesNotContain(analystTailMarker);
                Assertions.assertThat(value)
                        .as("event %s payload key %s leaks risk-review prompt body", ev.type(), entry.getKey())
                        .doesNotContain(riskTailMarker);
                Assertions.assertThat(value)
                        .as("event %s payload key %s leaks final-summary prompt body", ev.type(), entry.getKey())
                        .doesNotContain(summaryTailMarker);
                Assertions.assertThat(entry.getKey())
                        .as("event %s payload exposes a `systemMessage` key", ev.type())
                        .isNotEqualTo("systemMessage");
            }
        }
    }

    @Test
    void cryptoAgents_emitModelRoleSmartInLlmSucceededEvents() {
        enqueueLlm(analystResponseJson());
        enqueueLlm(riskReviewResponseJson());
        enqueueLlm(finalSummaryResponseJson());

        UUID runId = startMarketReviewRun();
        awaitTerminal(runId, RunStatus.SUCCEEDED);

        List<RuntimeEventResponse> llmSucceeded = collectEvents(runId).stream()
                .filter(e -> "LLM_CALL_SUCCEEDED".equals(e.type()))
                .toList();
        Assertions.assertThat(llmSucceeded).as("three LLM_CALL_SUCCEEDED events (analyst/risk/summary)").hasSize(3);
        for (RuntimeEventResponse ev : llmSucceeded) {
            Assertions.assertThat(ev.payload())
                    .as("LLM_CALL_SUCCEEDED for agent %s must carry model_role=smart", ev.agentId())
                    .containsEntry("model_role", "smart");
        }
    }

    private static Dispatcher cryptobotDispatcher() {
        // Fixture deliberately includes "fat" persistence-style fields (id, createdAt, updatedAt,
        // sectorTheme, priority, validFrom, valueJson, …). The lean projection inside
        // LoadCryptoMarketContextAgent must strip these before they reach the analyst LLM stage —
        // see analystSeesLeanMarketContext() below for the assertion.
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/api/cryptobot/snapshots/")) {
                    return json("{\"symbol\":\"BTCUSDT\",\"source\":\"deterministic-local\","
                            + "\"price\":67800.5,\"priceChangePct24h\":1.25,\"volumeBase24h\":28500.0,"
                            + "\"observedAt\":\"2026-01-01T00:00:00Z\"}");
                }
                if (path.startsWith("/api/cryptobot/watchlist")) {
                    return json("["
                            + "{\"id\":\"f1eee000-0001-7001-8001-000000000001\","
                            + "\"symbol\":\"BTCUSDT\",\"displayName\":\"Bitcoin\","
                            + "\"assetType\":\"CRYPTO\",\"sectorTheme\":\"CRYPTO_CORE\","
                            + "\"exchange\":\"BINANCE\",\"priority\":100,\"active\":true,"
                            + "\"notes\":null,\"createdAt\":\"2026-01-01T00:00:00Z\","
                            + "\"updatedAt\":\"2026-01-01T00:00:00Z\"},"
                            + "{\"id\":\"f1eee000-0001-7001-8001-000000000002\","
                            + "\"symbol\":\"ETHUSDT\",\"displayName\":\"Ethereum\","
                            + "\"assetType\":\"CRYPTO\",\"sectorTheme\":\"CRYPTO_CORE\","
                            + "\"exchange\":\"BINANCE\",\"priority\":100,\"active\":true,"
                            + "\"notes\":null,\"createdAt\":\"2026-01-01T00:00:00Z\","
                            + "\"updatedAt\":\"2026-01-01T00:00:00Z\"}"
                            + "]");
                }
                if (path.startsWith("/api/cryptobot/zones")) {
                    return json("["
                            + "{\"id\":\"f1eee000-0001-7001-8002-000000000001\","
                            + "\"symbol\":\"BTCUSDT\",\"timeframe\":\"1h\",\"zoneKind\":\"SUPPORT\","
                            + "\"lowerBound\":60000.0,\"upperBound\":62000.0,\"confidence\":0.6,"
                            + "\"validFrom\":\"2026-01-01T00:00:00Z\",\"validUntil\":null,"
                            + "\"label\":\"Local demand shelf\","
                            + "\"createdAt\":\"2026-01-01T00:00:00Z\","
                            + "\"updatedAt\":\"2026-01-01T00:00:00Z\"}"
                            + "]");
                }
                if (path.startsWith("/api/cryptobot/observations")) {
                    return json("["
                            + "{\"id\":\"f1eee000-0001-7001-8005-000000000001\","
                            + "\"symbol\":\"BTCUSDT\",\"venue\":\"BINANCE\","
                            + "\"observedAt\":\"2026-01-01T00:00:00Z\",\"timeframe\":\"1h\","
                            + "\"lastPrice\":67800.0,\"changePct24h\":1.2,"
                            + "\"volumeQuote24h\":2.85E10,\"spreadBps\":1.4,"
                            + "\"liquidityScore\":0.92,\"regime\":\"RANGE\","
                            + "\"createdAt\":\"2026-01-01T00:00:00Z\"}"
                            + "]");
                }
                if (path.startsWith("/api/cryptobot/journal")) {
                    return json("["
                            + "{\"id\":\"f1eee000-0001-7001-8003-000000000001\","
                            + "\"symbol\":\"BTCUSDT\",\"entryTime\":\"2026-01-01T00:00:00Z\","
                            + "\"title\":\"Range trade hypothesis\","
                            + "\"body\":\"Bias unchanged; waiting for break.\","
                            + "\"sentiment\":\"NEUTRAL\",\"tags\":\"range,observation\","
                            + "\"linkedWatchlistId\":\"f1eee000-0001-7001-8001-000000000001\","
                            + "\"createdAt\":\"2026-01-01T00:00:00Z\","
                            + "\"updatedAt\":\"2026-01-01T00:00:00Z\"}"
                            + "]");
                }
                if (path.startsWith("/api/cryptobot/indicators")) {
                    return json("["
                            + "{\"id\":\"f1eee000-0001-7001-8004-000000000001\","
                            + "\"symbol\":\"BTCUSDT\",\"timeframe\":\"1h\","
                            + "\"indicatorName\":\"RSI\",\"period\":14,"
                            + "\"computedAt\":\"2026-01-01T00:00:00Z\","
                            + "\"valueNumeric\":52.3,\"valueJson\":null,"
                            + "\"createdAt\":\"2026-01-01T00:00:00Z\"}"
                            + "]");
                }
                return new MockResponse().setResponseCode(404);
            }
        };
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private UUID startMarketReviewRun() {
        return webTestClient.post().uri("/api/runtime/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        """
                        {
                          "workflowId":"crypto.market-review.v1",
                          "input":"Review BTCUSDT and tell me if there is a signal.",
                          "correlationId":"crypto-mr-test"
                        }
                        """)
                .exchange().expectStatus().isCreated()
                .expectBody(RunResponse.class).returnResult().getResponseBody().runId();
    }

    private void awaitTerminal(UUID runId, RunStatus expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            RunStatus status = webTestClient.get().uri("/api/runtime/runs/{runId}", runId).exchange()
                    .expectStatus().isOk().expectBody(RunResponse.class).returnResult().getResponseBody().status();
            if (status == expected) {
                return;
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        Assertions.fail("Run did not reach " + expected + " in time");
    }

    private List<RuntimeEventResponse> collectEvents(UUID runId) {
        // Use the run-detail endpoint, which reads the stored event list synchronously and is
        // therefore not subject to the live-vs-replay race that affects {@code /events} when the
        // run terminates very quickly (the common case in tests).
        com.fasterxml.jackson.databind.JsonNode detail = webTestClient.get()
                .uri("/api/runtime/runs/{runId}", runId)
                .accept(MediaType.APPLICATION_JSON)
                .exchange().expectStatus().isOk()
                .expectBody(com.fasterxml.jackson.databind.JsonNode.class)
                .returnResult().getResponseBody();
        if (detail == null || !detail.has("events")) {
            return List.of();
        }
        try {
            return JSON.convertValue(
                    detail.get("events"),
                    JSON.getTypeFactory().constructCollectionType(List.class, RuntimeEventResponse.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- Canned LLM responses ----

    private static String analystResponseJson() {
        return """
                {
                  "bias": "NEUTRAL",
                  "summary": "BTCUSDT trades inside a defined range with mixed indicators.",
                  "setup": "Watch for a break above 70000 or rejection at 62000 support.",
                  "invalidations": ["Break below 60000", "Decisive close above 72000"],
                  "confidence": 0.55,
                  "risks": ["Limited observation history", "Macro driver could shift regime"]
                }
                """;
    }

    private static String riskReviewResponseJson() {
        return """
                {
                  "approved": true,
                  "warnings": ["Operator should validate macro context before acting"],
                  "riskLevel": "MEDIUM"
                }
                """;
    }

    private static String finalSummaryResponseJson() {
        return """
                {
                  "response": "BTCUSDT is range-bound; price 67800 sits between defined support and resistance. Analyst bias is NEUTRAL with modest confidence; risk review flags macro uncertainty as MEDIUM risk. This is market analysis, not financial advice."
                }
                """;
    }

    private void enqueueLlm(String content) {
        try {
            mockLlm.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {
                              "choices": [
                                {"message": {"content": %s}}
                              ],
                              "usage": {
                                "prompt_tokens": 120,
                                "completion_tokens": 80,
                                "total_tokens": 200
                              }
                            }
                            """.formatted(JSON.writeValueAsString(content))));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
