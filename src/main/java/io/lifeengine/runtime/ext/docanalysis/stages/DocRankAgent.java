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
 * Single stage of {@code doc.rank.v1} — selects the candidate document that best satisfies the
 * criteria. Enforces deterministically that the model's selection and scores reference only real
 * candidate ids (fails loudly otherwise). Event/log attributes carry only ids, counts, and lengths.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.doc-analysis.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DocRankAgent implements AgentExecutor {

    public static final String AGENT_ID = "doc-rank-agent";

    private final LlmClient llmClient;
    private final ObjectMapper mapper;
    private final PromptTemplateRegistry promptTemplateRegistry;
    private final int maxTokens;

    public DocRankAgent(
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

        DocAnalysisIo.RankInput parsed;
        try {
            parsed = DocAnalysisIo.readRankInput(mapper, request.input());
        } catch (Exception e) {
            return agentFailed(ctx, e);
        }
        Set<String> candidateIds =
                parsed.candidates().stream()
                        .map(DocAnalysisIo.DocumentRef::documentId)
                        .collect(Collectors.toUnmodifiableSet());
        Set<String> criterionIds =
                parsed.criteria().stream()
                        .map(DocAnalysisIo.Criterion::id)
                        .collect(Collectors.toUnmodifiableSet());

        PromptTemplate template =
                promptTemplateRegistry.require(
                        DocAnalysisPrompts.RANK_ID, DocAnalysisPrompts.VERSION_V1);
        List<LlmMessage> messages =
                List.of(
                        new LlmMessage("system", template.systemMessage()),
                        new LlmMessage("user", request.input()));

        return LlmAgentSupport.callLlm(
                        ctx, request.stageId(), AGENT_ID, llmClient, messages, template, maxTokens)
                .flatMap(
                        response -> {
                            try {
                                StrictAgentJson.DocRankOutput output =
                                        StrictAgentJson.parseDocRank(response.content());
                                validateAgainstInput(output, candidateIds, criterionIds);
                                String canonical = StrictAgentJson.canonicalJson(response.content());
                                ctx.putAgentOutput(AGENT_ID, canonical);

                                Map<String, String> attrs = new LinkedHashMap<>();
                                attrs.put("agentId", AGENT_ID);
                                attrs.put("subjectDocumentId", parsed.subject().documentId());
                                attrs.put("selectedDocumentId", output.selectedDocumentId());
                                attrs.put(
                                        "candidateCount",
                                        Integer.toString(parsed.candidates().size()));
                                attrs.put(
                                        "criterionCount", Integer.toString(parsed.criteria().size()));
                                attrs.put("confidence", output.confidence());
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

    private static void validateAgainstInput(
            StrictAgentJson.DocRankOutput output, Set<String> candidateIds, Set<String> criterionIds) {
        if (!candidateIds.contains(output.selectedDocumentId())) {
            throw new IllegalArgumentException(
                    "selectedDocumentId must be one of the candidates (got: "
                            + output.selectedDocumentId()
                            + ")");
        }
        for (StrictAgentJson.DocScore score : output.scores()) {
            if (!candidateIds.contains(score.documentId())) {
                throw new IllegalArgumentException(
                        "scores contains unknown documentId: " + score.documentId());
            }
            for (String criterionId : score.matched()) {
                if (!criterionIds.contains(criterionId)) {
                    throw new IllegalArgumentException(
                            "scores.matched contains unknown criterion id: " + criterionId);
                }
            }
            for (String criterionId : score.missing()) {
                if (!criterionIds.contains(criterionId)) {
                    throw new IllegalArgumentException(
                            "scores.missing contains unknown criterion id: " + criterionId);
                }
            }
        }
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
