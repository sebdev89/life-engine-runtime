package io.lifeengine.runtime.agents;

import io.lifeengine.runtime.llm.LlmMessage;
import io.lifeengine.runtime.llm.OpenAiCompatibleLlmClient;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class SummarizerAgent implements AgentExecutor {

    public static final String AGENT_ID = "summarizer-agent";

    private final OpenAiCompatibleLlmClient llmClient;

    public SummarizerAgent(OpenAiCompatibleLlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public Mono<AgentExecutionResult> execute(AgentExecutionRequest request, WorkflowRunContext ctx) {
        if (ctx.isCancelled()) {
            return Mono.error(new IllegalStateException("Run cancelled"));
        }
        ctx.emit("AGENT_STARTED", Map.of("agentId", AGENT_ID), false);

        List<LlmMessage> messages =
                List.of(
                        new LlmMessage(
                                "system",
                                "You are a concise summarizer. Reply with a short plain-text summary only."),
                        new LlmMessage("user", request.input()));

        return LlmAgentSupport.callLlm(ctx, AGENT_ID, llmClient, messages)
                .map(
                        response -> {
                            ctx.emit(
                                    "AGENT_COMPLETED",
                                    Map.of(
                                            "agentId",
                                            AGENT_ID,
                                            "summary",
                                            WorkflowRunContext.truncate(response.content(), 500)),
                                    false);
                            return AgentExecutionResult.ok(AGENT_ID, response.content());
                        })
                .onErrorResume(
                        error -> {
                            String msg = error.getMessage() == null ? error.toString() : error.getMessage();
                            ctx.emit("AGENT_FAILED", Map.of("agentId", AGENT_ID, "error", msg), false);
                            return Mono.error(error);
                        });
    }
}
