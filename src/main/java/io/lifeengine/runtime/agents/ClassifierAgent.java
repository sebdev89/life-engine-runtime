package io.lifeengine.runtime.agents;

import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.llm.LlmClient;
import io.lifeengine.runtime.llm.LlmMessage;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ClassifierAgent implements AgentExecutor {

    public static final String AGENT_ID = "classifier-agent";

    static final String SYSTEM_PROMPT =
            """
            You classify operational incident JSON from the summarizer agent.
            Reply with JSON only: no markdown, no code fences, no extra text.
            Schema:
            {"category":"INFO|ACTION|RISK|UNKNOWN","reason":"short explanation"}
            category must be exactly one of: INFO, ACTION, RISK, UNKNOWN
            """
                    .strip();

    private final LlmClient llmClient;

    public ClassifierAgent(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public Set<String> capabilities() {
        return Set.of("execute", "llm", "structured-output", "classify");
    }

    @Override
    public Mono<AgentExecutionResult> execute(AgentExecutionRequest request, WorkflowRunContext ctx) {
        if (ctx.isCancelled()) {
            return Mono.error(new IllegalStateException("Run cancelled"));
        }
        ctx.emit(EventType.AGENT_STARTED, Map.of("agentId", AGENT_ID), false);

        List<LlmMessage> messages =
                List.of(new LlmMessage("system", SYSTEM_PROMPT), new LlmMessage("user", request.input()));

        return LlmAgentSupport.callLlm(ctx, request.stageId(), AGENT_ID, llmClient, messages)
                .flatMap(
                        response -> {
                            try {
                                StrictAgentJson.ClassifierOutput parsed =
                                        StrictAgentJson.parseClassifier(response.content());
                                String canonical = StrictAgentJson.canonicalJson(response.content());
                                Map<String, String> completed = new LinkedHashMap<>();
                                completed.put("agentId", AGENT_ID);
                                completed.put("category", parsed.category());
                                completed.put("reason", WorkflowRunContext.truncate(parsed.reason(), 300));
                                completed.put("structured", WorkflowRunContext.truncate(canonical, 500));
                                completed.put(
                                        "incidentJson",
                                        WorkflowRunContext.truncate(request.input(), 500));
                                ctx.emit(EventType.AGENT_SUCCEEDED, completed, false);
                                return Mono.just(
                                        AgentExecutionResult.okWithClassification(
                                                AGENT_ID, canonical, parsed.category()));
                            } catch (IllegalArgumentException e) {
                                return agentParseFailed(ctx, e);
                            }
                        })
                .onErrorResume(
                        error -> {
                            if (error instanceof IllegalArgumentException) {
                                return Mono.error(error);
                            }
                            String msg = error.getMessage() == null ? error.toString() : error.getMessage();
                            ctx.emit(EventType.AGENT_FAILED, Map.of("agentId", AGENT_ID, "error", msg), false);
                            return Mono.error(error);
                        });
    }

    private Mono<AgentExecutionResult> agentParseFailed(
            WorkflowRunContext ctx, IllegalArgumentException e) {
        String msg = AGENT_ID + ": " + e.getMessage();
        ctx.emit(EventType.AGENT_FAILED, Map.of("agentId", AGENT_ID, "error", msg), false);
        return Mono.error(new IllegalArgumentException(msg, e));
    }
}
