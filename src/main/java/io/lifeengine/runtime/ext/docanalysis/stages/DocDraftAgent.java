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
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Single stage of {@code doc.draft.v1} — writes a grounded draft whose factual claims must cite
 * grounding documents. Enforces deterministically that every claim references a real grounding
 * documentId (fails loudly otherwise). Event/log attributes carry only ids, counts, and lengths.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.doc-analysis.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DocDraftAgent implements AgentExecutor {

    public static final String AGENT_ID = "doc-draft-agent";

    private final LlmClient llmClient;
    private final ObjectMapper mapper;
    private final PromptTemplateRegistry promptTemplateRegistry;
    private final int maxTokens;

    public DocDraftAgent(
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

        DocAnalysisIo.DraftInput parsed;
        try {
            parsed = DocAnalysisIo.readDraftInput(mapper, request.input());
        } catch (Exception e) {
            return agentFailed(ctx, e);
        }
        Set<String> groundingIds =
                parsed.groundingDocuments().stream()
                        .map(DocAnalysisIo.DocumentRef::documentId)
                        .collect(Collectors.toUnmodifiableSet());

        PromptTemplate template =
                promptTemplateRegistry.require(
                        DocAnalysisPrompts.DRAFT_ID, DocAnalysisPrompts.VERSION_V1);
        List<LlmMessage> messages =
                List.of(
                        new LlmMessage("system", template.systemMessage()),
                        new LlmMessage("user", request.input()));

        return LlmAgentSupport.callLlm(
                        ctx, request.stageId(), AGENT_ID, llmClient, messages, template, maxTokens)
                .flatMap(
                        response -> {
                            try {
                                StrictAgentJson.DocDraftOutput output =
                                        StrictAgentJson.parseDocDraft(response.content());
                                for (StrictAgentJson.DocClaim claim : output.claims()) {
                                    if (!groundingIds.contains(claim.groundedIn())) {
                                        throw new IllegalArgumentException(
                                                "claims.groundedIn must reference a groundingDocuments"
                                                        + " documentId (got: "
                                                        + claim.groundedIn()
                                                        + ")");
                                    }
                                }
                                String canonical = StrictAgentJson.canonicalJson(response.content());
                                ctx.putAgentOutput(AGENT_ID, canonical);

                                Map<String, String> attrs = new LinkedHashMap<>();
                                attrs.put("agentId", AGENT_ID);
                                attrs.put("subjectDocumentId", parsed.subject().documentId());
                                attrs.put(
                                        "groundingDocumentCount",
                                        Integer.toString(parsed.groundingDocuments().size()));
                                attrs.put("claimCount", Integer.toString(output.claims().size()));
                                attrs.put(
                                        "ungroundedContentDetected",
                                        Boolean.toString(output.ungroundedContentDetected()));
                                attrs.put("draftLength", Integer.toString(output.draft().length()));
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
