package io.lifeengine.runtime.tools.rag;

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

class RagQueryToolTest {

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

    @Test
    void jsonInput_200Response_returnsOkWithChunks() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"chunks":[
                          {"text":"Spring Boot auto-configures DataSource.","score":0.91,
                           "citationId":"c1","documentId":"d1","chunkId":"ck1","title":"Spring Docs"}
                        ]}"""));

        RagQueryTool tool = tool("default-collection");
        WorkflowRunContext ctx = testContext();

        ToolExecutionResult result = tool.execute(
                        request("{\"collectionId\":\"col-123\",\"query\":\"spring boot\",\"topK\":3}"), ctx)
                .block(Duration.ofSeconds(5));

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.success()).isTrue();
        JsonNode out = parse(result.output());
        Assertions.assertThat(out.get("status").asText()).isEqualTo("ok");
        Assertions.assertThat(out.get("chunks").size()).isEqualTo(1);
        Assertions.assertThat(out.get("chunks").get(0).get("text").asText()).isEqualTo("Spring Boot auto-configures DataSource.");
        Assertions.assertThat(out.get("chunks").get(0).get("score").asDouble()).isEqualTo(0.91);
        Assertions.assertThat(out.get("chunks").get(0).get("citationId").asText()).isEqualTo("c1");
    }

    @Test
    void jsonInput_outputStoredInContext() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"chunks\":[]}"));

        WorkflowRunContext ctx = testContext();
        tool("col-default").execute(request("{\"collectionId\":\"col-x\",\"query\":\"q\"}"), ctx)
                .block(Duration.ofSeconds(5));

        Assertions.assertThat(ctx.toolOutputs().get("rag.query")).isNotNull();
    }

    @Test
    void plainStringInput_usesDefaultCollectionId() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"chunks\":[]}"));

        RagQueryTool tool = tool("my-default-collection");
        ToolExecutionResult result = tool.execute(request("what is pricing"), testContext())
                .block(Duration.ofSeconds(5));

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.success()).isTrue();
        JsonNode out = parse(result.output());
        Assertions.assertThat(out.get("collectionId").asText()).isEqualTo("my-default-collection");
        Assertions.assertThat(out.get("status").asText()).isEqualTo("ok");
    }

    @Test
    void blankCollectionId_returnsErrorWithoutHttpCall() {
        int countBefore = mockServer.getRequestCount();
        RagQueryTool tool = tool("");
        WorkflowRunContext ctx = testContext();

        ToolExecutionResult result = tool.execute(request("find something"), ctx)
                .block(Duration.ofSeconds(5));

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.success()).isTrue();
        JsonNode out = parse(result.output());
        Assertions.assertThat(out.get("status").asText()).isEqualTo("error");
        Assertions.assertThat(mockServer.getRequestCount()).isEqualTo(countBefore);
    }

    @Test
    void blankQuery_returnsErrorWithoutHttpCall() {
        RagQueryTool tool = tool("some-collection");
        ToolExecutionResult result = tool.execute(request("{\"collectionId\":\"col\",\"query\":\"\"}"), testContext())
                .block(Duration.ofSeconds(5));

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.success()).isTrue();
        JsonNode out = parse(result.output());
        Assertions.assertThat(out.get("status").asText()).isEqualTo("error");
    }

    @Test
    void server500_returnsStatusError_doesNotFailWorkflow() {
        mockServer.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

        RagQueryTool tool = tool("col");
        ToolExecutionResult result = tool.execute(request("{\"collectionId\":\"col\",\"query\":\"q\"}"), testContext())
                .block(Duration.ofSeconds(5));

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.success()).isTrue();
        Assertions.assertThat(parse(result.output()).get("status").asText()).isEqualTo("error");
    }

    @Test
    void server429_returnsStatusError_doesNotFailWorkflow() {
        mockServer.enqueue(new MockResponse().setResponseCode(429).setBody("Too Many Requests"));

        RagQueryTool tool = tool("col");
        ToolExecutionResult result = tool.execute(request("{\"collectionId\":\"col\",\"query\":\"q\"}"), testContext())
                .block(Duration.ofSeconds(5));

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.success()).isTrue();
        Assertions.assertThat(parse(result.output()).get("status").asText()).isEqualTo("error");
    }

    @Test
    void topKFromInput_clamped() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"chunks\":[]}"));

        RagQueryTool tool = tool("col");
        tool.execute(request("{\"collectionId\":\"col\",\"query\":\"q\",\"topK\":100}"), testContext())
                .block(Duration.ofSeconds(5));

        var recorded = mockServer.takeRequest();
        String body = recorded.getBody().readUtf8();
        JsonNode req = JSON.readTree(body);
        Assertions.assertThat(req.get("topK").asInt()).isLessThanOrEqualTo(20);
    }

    @Test
    void emptyChunksResponse_returnsOkStatus() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"chunks\":[]}"));

        ToolExecutionResult result = tool("col")
                .execute(request("{\"collectionId\":\"col\",\"query\":\"q\"}"), testContext())
                .block(Duration.ofSeconds(5));

        Assertions.assertThat(parse(result.output()).get("status").asText()).isEqualTo("ok");
        Assertions.assertThat(parse(result.output()).get("chunks").size()).isEqualTo(0);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private RagQueryTool tool(String defaultCollectionId) {
        String baseUrl = "http://localhost:" + mockServer.getPort();
        WebClient wc = WebClient.builder().baseUrl(baseUrl).build();
        RagProperties props = new RagProperties(true, baseUrl, defaultCollectionId, 5, Duration.ofSeconds(10));
        return new RagQueryTool(wc, props, JSON);
    }

    private ToolExecutionRequest request(String input) {
        return new ToolExecutionRequest(UUID.randomUUID(), "rag.query", input, Map.of());
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
