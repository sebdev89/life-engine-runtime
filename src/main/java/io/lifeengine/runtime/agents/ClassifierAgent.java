package io.lifeengine.runtime.agents;

import io.lifeengine.runtime.llm.LlmMessage;
import io.lifeengine.runtime.llm.OpenAiCompatibleLlmClient;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ClassifierAgent implements AgentExecutor {

    public static final String AGENT_ID = "classifier-agent";

    private static final Pattern CLASSIFICATION =
            Pattern.compile("\\b(INFO|ACTION|RISK|UNKNOWN)\\b", Pattern.CASE_INSENSITIVE);

    private final OpenAiCompatibleLlmClient llmClient;

    public ClassifierAgent(OpenAiCompatibleLlmClient llmClient) {
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
                                "Classify the user summary into exactly one label: INFO, ACTION, RISK, or UNKNOWN."
                                        + " Reply with the label only."),
                        new LlmMessage("user", request.input()));

        return LlmAgentSupport.callLlm(ctx, AGENT_ID, llmClient, messages)
                .map(
                        response -> {
                            String classification = parseClassification(response.content());
                            ctx.emit(
                                    "AGENT_COMPLETED",
                                    Map.of(
                                            "agentId",
                                            AGENT_ID,
                                            "classification",
                                            classification,
                                            "summary",
                                            WorkflowRunContext.truncate(request.input(), 200)),
                                    false);
                            return AgentExecutionResult.okWithClassification(
                                    AGENT_ID, response.content(), classification);
                        })
                .onErrorResume(
                        error -> {
                            String msg = error.getMessage() == null ? error.toString() : error.getMessage();
                            ctx.emit("AGENT_FAILED", Map.of("agentId", AGENT_ID, "error", msg), false);
                            return Mono.error(error);
                        });
    }

    static String parseClassification(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNKNOWN";
        }
        var matcher = CLASSIFICATION.matcher(raw.toUpperCase(Locale.ROOT));
        if (matcher.find()) {
            return matcher.group(1).toUpperCase(Locale.ROOT);
        }
        return "UNKNOWN";
    }
}
