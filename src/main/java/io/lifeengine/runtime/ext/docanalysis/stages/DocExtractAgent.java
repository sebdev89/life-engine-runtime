package io.lifeengine.runtime.ext.docanalysis.stages;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.agents.LlmAgentSupport;
import io.lifeengine.runtime.agents.StrictAgentJson;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.ext.docanalysis.DocAnalysisIo;
import io.lifeengine.runtime.ext.docanalysis.DocAnalysisPrompts;
import io.lifeengine.runtime.llm.LlmClient;
import io.lifeengine.runtime.llm.LlmMessage;
import io.lifeengine.runtime.prompts.PromptTemplate;
import io.lifeengine.runtime.prompts.PromptTemplateRegistry;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Single stage of {@code doc.extract.v1} — extracts requirements with literal evidence spans from
 * one document. Event/log attributes carry only ids, counts, and lengths — never document content.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.doc-analysis.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DocExtractAgent implements AgentExecutor {

    public static final String AGENT_ID = "doc-extract-agent";

    private final LlmClient llmClient;
    private final ObjectMapper mapper;
    private final PromptTemplateRegistry promptTemplateRegistry;
    private final int maxTokens;

    public DocExtractAgent(
            LlmClient llmClient,
            ObjectMapper mapper,
            PromptTemplateRegistry promptTemplateRegistry,
            @Value("${lifeengine.runtime.ext.doc-analysis.max-tokens:1024}") int maxTokens) {
        this.llmClient = llmClient;
        this.mapper = mapper;
        this.promptTemplateRegistry = promptTemplateRegistry;
        this.maxTokens = maxTokens;
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
        ctx.emit(EventType.AGENT_STARTED, Map.of("agentId", AGENT_ID), false);

        DocAnalysisIo.ExtractInput parsed;
        try {
            parsed = DocAnalysisIo.readExtractInput(mapper, request.input());
        } catch (Exception e) {
            return agentFailed(ctx, e);
        }

        PromptTemplate template =
                promptTemplateRegistry.require(
                        DocAnalysisPrompts.EXTRACT_ID, DocAnalysisPrompts.VERSION_V1);
        List<LlmMessage> messages =
                List.of(
                        new LlmMessage("system", template.systemMessage()),
                        new LlmMessage("user", request.input()));

        return LlmAgentSupport.callLlm(
                        ctx, request.stageId(), AGENT_ID, llmClient, messages, template, maxTokens)
                .flatMap(
                        response -> {
                            try {
                                StrictAgentJson.DocExtractOutput output =
                                        StrictAgentJson.parseDocExtract(response.content());
                                String canonical = StrictAgentJson.canonicalJson(response.content());
                                ctx.putAgentOutput(AGENT_ID, canonical);

                                Map<String, String> attrs = new LinkedHashMap<>();
                                attrs.put("agentId", AGENT_ID);
                                attrs.put("documentId", parsed.document().documentId());
                                attrs.put(
                                        "documentLength",
                                        Integer.toString(parsed.document().content().length()));
                                attrs.put(
                                        "requirementCount",
                                        Integer.toString(output.requirements().size()));
                                attrs.put("language", output.language());
                                attrs.put("outputLength", Integer.toString(canonical.length()));
                                ctx.emit(EventType.AGENT_SUCCEEDED, attrs, false);
                                return Mono.just(AgentExecutionResult.ok(AGENT_ID, canonical));
                            } catch (IllegalArgumentException e) {
                                return agentFailed(ctx, e);
                            }
                        })
                .onErrorResume(
                        error -> {
                            if (error instanceof IllegalArgumentException) {
                                return Mono.error(error);
                            }
                            return agentFailed(ctx, error);
                        });
    }

    private Mono<AgentExecutionResult> agentFailed(WorkflowRunContext ctx, Throwable error) {
        String msg = error.getMessage() == null ? error.toString() : error.getMessage();
        ctx.emit(EventType.AGENT_FAILED, Map.of("agentId", AGENT_ID, "error", msg), false);
        return Mono.error(
                error instanceof IllegalArgumentException
                        ? new IllegalArgumentException(AGENT_ID + ": " + msg, error)
                        : error);
    }
}
