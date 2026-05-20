package io.lifeengine.runtime.agents;

import io.lifeengine.runtime.llm.LlmCallException;
import io.lifeengine.runtime.llm.LlmMessage;
import io.lifeengine.runtime.llm.LlmRequest;
import io.lifeengine.runtime.llm.LlmResponse;
import io.lifeengine.runtime.llm.OpenAiCompatibleLlmClient;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** Shared LLM call + mandatory LLM_* runtime events. */
public final class LlmAgentSupport {

    private static final Logger log = LoggerFactory.getLogger(LlmAgentSupport.class);

    private LlmAgentSupport() {}

    public static Mono<LlmResponse> callLlm(
            WorkflowRunContext ctx,
            String agentId,
            OpenAiCompatibleLlmClient llmClient,
            List<LlmMessage> messages) {
        if (ctx.isCancelled()) {
            return Mono.error(new IllegalStateException("Run cancelled"));
        }
        String model = llmClient.defaultModel();
        String promptPreview = messages.stream().map(LlmMessage::content).reduce("", (a, b) -> a + "\n" + b);
        Instant started = Instant.now();
        log.info(
                "Calling vLLM model {} for agent {} runId={} endpoint={}",
                model,
                agentId,
                ctx.runId(),
                llmClient.chatCompletionsEndpoint());
        ctx.emit("LLM_REQUESTED", WorkflowRunContext.previewAttrs(agentId, model, promptPreview), false);

        return llmClient
                .chatCompletion(new LlmRequest(model, messages))
                .doOnSuccess(
                        response -> {
                            long latencyMs = Math.max(0, Instant.now().toEpochMilli() - started.toEpochMilli());
                            ctx.emit(
                                    "LLM_COMPLETED",
                                    WorkflowRunContext.llmCompletedAttrs(
                                            agentId,
                                            model,
                                            latencyMs,
                                            response.content(),
                                            response.usage()),
                                    false);
                        })
                .onErrorResume(
                        error -> {
                            long latencyMs = Math.max(0, Instant.now().toEpochMilli() - started.toEpochMilli());
                            if (error instanceof LlmCallException llmEx) {
                                ctx.emit(
                                        "LLM_FAILED",
                                        WorkflowRunContext.llmFailedAttrs(agentId, model, latencyMs, llmEx),
                                        false);
                                log.error(
                                        "LLM_FAILED agent={} runId={} status={} endpoint={}",
                                        agentId,
                                        ctx.runId(),
                                        llmEx.statusCode(),
                                        llmEx.endpoint(),
                                        error);
                            } else {
                                String message =
                                        error.getMessage() == null ? error.toString() : error.getMessage();
                                ctx.emit(
                                        "LLM_FAILED",
                                        WorkflowRunContext.llmFailedAttrs(agentId, model, message, latencyMs),
                                        false);
                                log.error(
                                        "LLM_FAILED agent={} runId={} (non-http)",
                                        agentId,
                                        ctx.runId(),
                                        error);
                            }
                            return Mono.error(error);
                        });
    }
}
