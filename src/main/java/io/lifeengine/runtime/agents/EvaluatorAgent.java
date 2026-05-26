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

/** Generic structured evaluator MLP (not wired into demo.llm.workflow by default). */
@Component
public class EvaluatorAgent implements AgentExecutor {

    public static final String AGENT_ID = "evaluator-agent";

    static final String SYSTEM_PROMPT =
            """
            You evaluate whether operational JSON meets policy.
            Reply with JSON only: no markdown, no code fences, no extra text.
            Schema: {"verdict":"PASS|FAIL|REVIEW","rationale":"short explanation"}
            """
                    .strip();

    private final LlmClient llmClient;

    public EvaluatorAgent(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public Set<String> capabilities() {
        return Set.of("execute", "llm", "structured-output", "evaluate");
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
                                StrictAgentJson.EvaluatorOutput parsed =
                                        StrictAgentJson.parseEvaluator(response.content());
                                String canonical = StrictAgentJson.canonicalJson(response.content());
                                Map<String, String> completed = new LinkedHashMap<>();
                                completed.put("agentId", AGENT_ID);
                                completed.put("verdict", parsed.verdict());
                                completed.put("rationale", WorkflowRunContext.truncate(parsed.rationale(), 300));
                                completed.put("structured", WorkflowRunContext.truncate(canonical, 500));
                                ctx.emit(EventType.AGENT_SUCCEEDED, completed, false);
                                return Mono.just(AgentExecutionResult.ok(AGENT_ID, canonical));
                            } catch (IllegalArgumentException e) {
                                String msg = AGENT_ID + ": " + e.getMessage();
                                ctx.emit(EventType.AGENT_FAILED, Map.of("agentId", AGENT_ID, "error", msg), false);
                                return Mono.error(new IllegalArgumentException(msg, e));
                            }
                        });
    }
}
