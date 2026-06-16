package io.lifeengine.runtime.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.core.InMemoryRunStore;
import io.lifeengine.runtime.events.RunEventPublisher;
import io.lifeengine.runtime.tools.ToolExecutionRequest;
import io.lifeengine.runtime.tools.ToolExecutionResult;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class SearchWebToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static MockWebServer mockServer;

    @BeforeAll
    static void startServer() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        if (mockServer != null) {
            mockServer.shutdown();
        }
    }

    // ── mock provider tests ────────────────────────────────────────────────

    @Test
    void mockProvider_returnsOkWithFixedResults() {
        SearchWebTool tool = toolWith(new MockSearchProvider());
        WorkflowRunContext ctx = testContext();

        ToolExecutionResult result = tool.execute(
                        request("{\"query\":\"spring boot\",\"maxResults\":3}"), ctx)
                .block(Duration.ofSeconds(2));

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.success()).isTrue();

        JsonNode out = parse(result.output());
        Assertions.assertThat(out.get("status").asText()).isEqualTo("ok");
        Assertions.assertThat(out.get("provider").asText()).isEqualTo("mock");
        Assertions.assertThat(out.get("results").size()).isEqualTo(3);
        Assertions.assertThat(out.get("results").get(0).get("title").asText()).isNotBlank();
        Assertions.assertThat(out.get("results").get(0).get("url").asText()).isNotBlank();
        Assertions.assertThat(out.get("results").get(0).get("snippet").asText()).isNotBlank();
    }

    @Test
    void mockProvider_defaultMaxResultsWhenNotSpecified() {
        SearchWebTool tool = toolWith(new MockSearchProvider());
        WorkflowRunContext ctx = testContext();

        ToolExecutionResult result = tool.execute(
                        request("{\"query\":\"test\"}"), ctx)
                .block(Duration.ofSeconds(2));

        Assertions.assertThat(result).isNotNull();
        JsonNode out = parse(result.output());
        // MockSearchProvider has 3 results; default maxResults=5 → capped at 3
        Assertions.assertThat(out.get("results").size()).isEqualTo(3);
    }

    @Test
    void mockProvider_emptyQueryStillSucceeds() {
        SearchWebTool tool = toolWith(new MockSearchProvider());
        WorkflowRunContext ctx = testContext();

        ToolExecutionResult result = tool.execute(request("{}"), ctx)
                .block(Duration.ofSeconds(2));

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.success()).isTrue();
        JsonNode out = parse(result.output());
        Assertions.assertThat(out.get("status").asText()).isEqualTo("ok");
    }

    @Test
    void mockProvider_outputStoredInContext() {
        SearchWebTool tool = toolWith(new MockSearchProvider());
        WorkflowRunContext ctx = testContext();

        tool.execute(request("{\"query\":\"test\"}"), ctx).block(Duration.ofSeconds(2));

        Assertions.assertThat(ctx.toolOutputs()).containsKey("search.web");
    }

    // ── disabled provider tests ────────────────────────────────────────────

    @Test
    void tavilyProvider_noApiKey_returnsStatusDisabled() {
        TavilySearchProvider tavily = tavilyProvider("");
        SearchWebTool tool = toolWith(tavily);
        WorkflowRunContext ctx = testContext();

        ToolExecutionResult result = tool.execute(
                        request("{\"query\":\"test\"}"), ctx)
                .block(Duration.ofSeconds(2));

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.success()).isTrue();

        JsonNode out = parse(result.output());
        Assertions.assertThat(out.get("status").asText()).isEqualTo("disabled");
        Assertions.assertThat(out.get("results").size()).isZero();
        Assertions.assertThat(out.get("provider").asText()).isEqualTo("tavily");
    }

    @Test
    void tavilyProvider_noApiKey_makesNoHttpCall() {
        int requestsBefore = mockServer.getRequestCount();
        TavilySearchProvider tavily = tavilyProvider("");
        SearchWebTool tool = toolWith(tavily);

        tool.execute(request("{\"query\":\"test\"}"), testContext())
                .block(Duration.ofSeconds(2));

        Assertions.assertThat(mockServer.getRequestCount()).isEqualTo(requestsBefore);
    }

    // ── tavily provider tests ──────────────────────────────────────────────

    @Test
    void tavilyProvider_200Response_returnsOkWithMappedResults() throws Exception {
        String tavilyResponse = JSON.writeValueAsString(Map.of(
                "results", java.util.List.of(
                        Map.of("title", "Result One", "url", "https://example.com/1", "content", "Snippet one"),
                        Map.of("title", "Result Two", "url", "https://example.com/2", "content", "Snippet two"))));

        mockServer.enqueue(new MockResponse()
                .setBody(tavilyResponse)
                .addHeader("Content-Type", "application/json"));

        TavilySearchProvider tavily = tavilyProvider("tvly-test-key");
        SearchWebTool tool = toolWith(tavily);
        WorkflowRunContext ctx = testContext();

        ToolExecutionResult result = tool.execute(
                        request("{\"query\":\"test query\",\"maxResults\":5}"), ctx)
                .block(Duration.ofSeconds(5));

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.success()).isTrue();

        JsonNode out = parse(result.output());
        Assertions.assertThat(out.get("status").asText()).isEqualTo("ok");
        Assertions.assertThat(out.get("provider").asText()).isEqualTo("tavily");
        Assertions.assertThat(out.get("results").size()).isEqualTo(2);
        Assertions.assertThat(out.get("results").get(0).get("title").asText()).isEqualTo("Result One");
        Assertions.assertThat(out.get("results").get(0).get("snippet").asText()).isEqualTo("Snippet one");
    }

    @Test
    void tavilyProvider_500Response_returnsStatusError_doesNotFailWorkflow() {
        mockServer.enqueue(new MockResponse().setResponseCode(500));

        TavilySearchProvider tavily = tavilyProvider("tvly-test-key");
        SearchWebTool tool = toolWith(tavily);

        StepVerifier.create(tool.execute(request("{\"query\":\"test\"}"), testContext()))
                .assertNext(result -> {
                    Assertions.assertThat(result.success()).isTrue();
                    JsonNode out = parse(result.output());
                    Assertions.assertThat(out.get("status").asText()).isEqualTo("error");
                    Assertions.assertThat(out.get("results").size()).isZero();
                })
                .verifyComplete();
    }

    @Test
    void tavilyProvider_429Response_returnsStatusError_doesNotFailWorkflow() {
        mockServer.enqueue(new MockResponse().setResponseCode(429));

        TavilySearchProvider tavily = tavilyProvider("tvly-test-key");
        SearchWebTool tool = toolWith(tavily);

        StepVerifier.create(tool.execute(request("{\"query\":\"test\"}"), testContext()))
                .assertNext(result -> {
                    Assertions.assertThat(result.success()).isTrue();
                    JsonNode out = parse(result.output());
                    Assertions.assertThat(out.get("status").asText()).isEqualTo("error");
                })
                .verifyComplete();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private SearchWebTool toolWith(SearchProvider provider) {
        return new SearchWebTool(provider, JSON);
    }

    private TavilySearchProvider tavilyProvider(String apiKey) {
        String baseUrl = mockServer.url("/").toString();
        WebClient wc = WebClient.builder().baseUrl(baseUrl).build();
        return new TavilySearchProvider(wc, apiKey, Duration.ofSeconds(3));
    }

    private ToolExecutionRequest request(String input) {
        return new ToolExecutionRequest(UUID.randomUUID(), "search.web", input, Map.of());
    }

    private WorkflowRunContext testContext() {
        return new WorkflowRunContext(
                UUID.randomUUID(),
                "test.workflow.v1",
                "corr-test",
                "{}",
                new InMemoryRunStore(),
                new RunEventPublisher(),
                new AtomicBoolean(false));
    }

    private JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("Output is not valid JSON: " + json, e);
        }
    }
}
