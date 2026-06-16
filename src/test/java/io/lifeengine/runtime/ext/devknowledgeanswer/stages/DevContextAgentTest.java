package io.lifeengine.runtime.ext.devknowledgeanswer.stages;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.core.InMemoryRunStore;
import io.lifeengine.runtime.events.RunEventPublisher;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class DevContextAgentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void execute_buildsContextWithEvidenceMetadata() {
        DevContextAgent agent = new DevContextAgent(MAPPER);
        WorkflowRunContext ctx = testContext();

        String input =
                """
                {
                  "question": "¿Dónde se valida el JWT?",
                  "knowledgeContext": {
                    "retrievedChunks": [
                      {
                        "documentId": "doc-1",
                        "chunkId": "chunk-1",
                        "title": "JWT validation",
                        "content": "RuntimeJwtAuthenticationWebFilter validates JWT.",
                        "score": 0.91
                      },
                      {
                        "documentId": "doc-2",
                        "chunkId": "chunk-2",
                        "title": "Security filter",
                        "content": "Bearer token is parsed on each request.",
                        "score": 0.82
                      }
                    ]
                  }
                }
                """;

        StepVerifier.create(agent.execute(request(ctx, input), ctx))
                .assertNext(
                        result -> {
                            assertThat(result.agentId()).isEqualTo(DevContextAgent.AGENT_ID);
                            assertThat(result.output()).contains("chunkCount");
                            assertThat(result.output()).contains("\"hasEvidence\":true");
                            assertThat(result.output()).contains("RuntimeJwtAuthenticationWebFilter");
                        })
                .verifyComplete();
    }

    @Test
    void execute_marksNoEvidenceWhenChunksEmpty() {
        DevContextAgent agent = new DevContextAgent(MAPPER);
        WorkflowRunContext ctx = testContext();

        String input =
                """
                {
                  "question": "¿Dónde se valida el JWT?",
                  "knowledgeContext": {"retrievedChunks": []}
                }
                """;

        StepVerifier.create(agent.execute(request(ctx, input), ctx))
                .assertNext(
                        result -> {
                            assertThat(result.output()).contains("\"chunkCount\":0");
                            assertThat(result.output()).contains("\"hasEvidence\":false");
                        })
                .verifyComplete();
    }

    private static AgentExecutionRequest request(WorkflowRunContext ctx, String input) {
        return new AgentExecutionRequest(
                ctx.runId(),
                DevContextAgent.AGENT_ID,
                "dev-context",
                input,
                Map.of());
    }

    @Test
    void execute_withRagQueryToolOutput_usesRagChunksOverEmptyInput() {
        DevContextAgent agent = new DevContextAgent(MAPPER);

        String workflowInput =
                """
                {
                  "question": "What is reactive programming?",
                  "knowledgeContext": {"retrievedChunks": []}
                }
                """;
        WorkflowRunContext ctx = testContextWithInput(workflowInput);

        String ragOutput =
                """
                {"collectionId":"test","chunks":[
                  {"text":"Reactive programming is non-blocking.","score":0.88,
                   "citationId":"","documentId":"d1","chunkId":"c1","title":"Reactive"}
                ],"status":"ok"}
                """;
        ctx.putToolOutput("rag.query", ragOutput);

        // request.input() is the rag-query tool output (simulating stage chain)
        String ragQueryOutputAsInput = ragOutput;

        StepVerifier.create(agent.execute(request(ctx, ragQueryOutputAsInput), ctx))
                .assertNext(
                        result -> {
                            assertThat(result.output()).contains("\"chunkCount\":1");
                            assertThat(result.output()).contains("\"hasEvidence\":true");
                            assertThat(result.output()).contains("Reactive programming is non-blocking");
                        })
                .verifyComplete();
    }

    private static WorkflowRunContext testContext() {
        return testContextWithInput("{}");
    }

    private static WorkflowRunContext testContextWithInput(String input) {
        UUID runId = UUID.randomUUID();
        InMemoryRunStore store = new InMemoryRunStore();
        return new WorkflowRunContext(
                runId,
                "dev.knowledge-answer.v1",
                "corr-test",
                input,
                store,
                new RunEventPublisher(),
                new AtomicBoolean(false));
    }
}
