package io.lifeengine.runtime.ext.devknowledgeanswer.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.agents.LlmAgentSupport;
import io.lifeengine.runtime.agents.StrictAgentJson;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.ext.devknowledgeanswer.DevKnowledgeAnswerPrompts;
import io.lifeengine.runtime.llm.LlmClient;
import io.lifeengine.runtime.llm.LlmMessage;
import io.lifeengine.runtime.llm.LlmResponse;
import io.lifeengine.runtime.prompts.PromptTemplate;
import io.lifeengine.runtime.prompts.PromptTemplateRegistry;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Stage 2 — LLM-backed grounded answer. Uses context-stage evidence and attaches sources from
 * retrieved chunks when the model omits them.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.dev-knowledge-answer.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DevAnswerAgent implements AgentExecutor {

    public static final String AGENT_ID = "dev-answer-agent";

    private final LlmClient llmClient;
    private final ObjectMapper mapper;
    private final PromptTemplateRegistry promptTemplateRegistry;

    public DevAnswerAgent(
            LlmClient llmClient, ObjectMapper mapper, PromptTemplateRegistry promptTemplateRegistry) {
        this.llmClient = llmClient;
        this.mapper = mapper;
        this.promptTemplateRegistry = promptTemplateRegistry;
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

        String knowledgeContextJson;
        boolean hasEvidence;
        String userInput;
        try {
            knowledgeContextJson =
                    request.input() == null || request.input().isBlank() ? "{}" : request.input();
            JsonNode contextNode = mapper.readTree(knowledgeContextJson);
            hasEvidence = contextNode.path("hasEvidence").asBoolean(false);
            if (!hasEvidence) {
                ctx.addWarning("No retrieved chunks — answer must state insufficient evidence");
            }
            ObjectNode combined = mapper.createObjectNode();
            combined.set("source", mapper.readTree(ctx.input()));
            combined.set("knowledgeContext", mapper.readTree(knowledgeContextJson));
            userInput = mapper.writeValueAsString(combined);
        } catch (Exception e) {
            return agentFailed(ctx, e);
        }

        PromptTemplate template =
                promptTemplateRegistry.require(
                        DevKnowledgeAnswerPrompts.ANSWER_ID, DevKnowledgeAnswerPrompts.VERSION_V1);
        List<LlmMessage> messages =
                List.of(
                        new LlmMessage("system", template.systemMessage()),
                        new LlmMessage("user", userInput));

        return callAndParse(ctx, request, template, messages, knowledgeContextJson, hasEvidence, false)
                .onErrorResume(
                        IllegalArgumentException.class,
                        firstError ->
                                callAndParse(
                                                ctx,
                                                request,
                                                template,
                                                withJsonRetryReminder(messages),
                                                knowledgeContextJson,
                                                hasEvidence,
                                                true)
                                        .onErrorMap(
                                                retryError ->
                                                        new IllegalArgumentException(
                                                                AGENT_ID
                                                                        + ": JSON parse failed after retry: "
                                                                        + retryError.getMessage(),
                                                                retryError)));
    }

    private Mono<AgentExecutionResult> callAndParse(
            WorkflowRunContext ctx,
            AgentExecutionRequest request,
            PromptTemplate template,
            List<LlmMessage> messages,
            String knowledgeContextJson,
            boolean hasEvidence,
            boolean retried) {
        return LlmAgentSupport.callLlm(ctx, request.stageId(), AGENT_ID, llmClient, messages, template)
                .flatMap(
                        response -> {
                            try {
                                return Mono.just(
                                        buildSuccessResult(
                                                ctx,
                                                knowledgeContextJson,
                                                hasEvidence,
                                                response,
                                                retried));
                            } catch (IllegalArgumentException e) {
                                return Mono.error(e);
                            } catch (Exception e) {
                                return agentFailed(ctx, e);
                            }
                        });
    }

    private AgentExecutionResult buildSuccessResult(
            WorkflowRunContext ctx,
            String knowledgeContextJson,
            boolean hasEvidence,
            LlmResponse response,
            boolean retried)
            throws Exception {
        StrictAgentJson.DevKnowledgeAnswerOutput answer =
                StrictAgentJson.parseDevKnowledgeAnswer(response.content());
        String canonical =
                finalizeAnswer(knowledgeContextJson, response.content(), hasEvidence, ctx);
        int sourcesCount = mapper.readTree(canonical).path("sources").size();

        ctx.putAgentOutput(AGENT_ID, canonical);

        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("agentId", AGENT_ID);
        attrs.put("confidence", answer.confidence());
        attrs.put("sourcesCount", Integer.toString(sourcesCount));
        attrs.put("hasEvidence", Boolean.toString(hasEvidence));
        attrs.put("jsonRetried", Boolean.toString(retried));
        attrs.put("answerPreview", WorkflowRunContext.truncate(answer.answer(), 500));
        attrs.put("structured", WorkflowRunContext.truncate(canonical, 500));
        ctx.emit(EventType.AGENT_SUCCEEDED, attrs, false);

        return AgentExecutionResult.ok(AGENT_ID, canonical);
    }

    private static List<LlmMessage> withJsonRetryReminder(List<LlmMessage> original) {
        List<LlmMessage> retryMessages = new ArrayList<>(original);
        retryMessages.add(new LlmMessage("user", DevKnowledgeAnswerPrompts.ANSWER_JSON_RETRY_REMINDER));
        return List.copyOf(retryMessages);
    }

    private String finalizeAnswer(
            String knowledgeContextJson, String rawReply, boolean hasEvidence, WorkflowRunContext ctx)
            throws Exception {
        ObjectNode node = (ObjectNode) mapper.readTree(StrictAgentJson.canonicalJson(rawReply));
        node.put("confidence", node.path("confidence").asText("low").toLowerCase());
        filterSourcesToKnownChunks(node, knowledgeContextJson);
        attachSourcesFromContext(node, knowledgeContextJson);
        if (!hasEvidence && node.path("sources").isArray() && node.get("sources").isEmpty()) {
            ctx.addWarning("Answer produced without evidence and without attached sources");
        }
        return mapper.writeValueAsString(node);
    }

    private void filterSourcesToKnownChunks(ObjectNode node, String knowledgeContextJson) throws Exception {
        JsonNode sources = node.get("sources");
        if (sources == null || !sources.isArray() || sources.isEmpty()) {
            return;
        }
        Set<String> knownChunkIds = knownChunkIds(knowledgeContextJson);
        ArrayNode filtered = mapper.createArrayNode();
        for (JsonNode source : sources) {
            String chunkId = source.path("chunkId").asText("");
            if (!chunkId.isBlank() && knownChunkIds.contains(chunkId)) {
                filtered.add(source);
            }
        }
        node.set("sources", filtered);
    }

    private Set<String> knownChunkIds(String knowledgeContextJson) throws Exception {
        Set<String> ids = new HashSet<>();
        JsonNode chunks = mapper.readTree(knowledgeContextJson).path("retrievedChunks");
        if (!chunks.isArray()) {
            return ids;
        }
        for (JsonNode chunk : chunks) {
            String chunkId = chunk.path("chunkId").asText("");
            if (!chunkId.isBlank()) {
                ids.add(chunkId);
            }
        }
        return ids;
    }

    private void attachSourcesFromContext(ObjectNode node, String knowledgeContextJson) throws Exception {
        if (node.has("sources") && node.get("sources").isArray() && !node.get("sources").isEmpty()) {
            return;
        }
        JsonNode contextNode = mapper.readTree(knowledgeContextJson);
        JsonNode chunks = contextNode.get("retrievedChunks");
        if (chunks == null || !chunks.isArray() || chunks.isEmpty()) {
            return;
        }
        var sources = mapper.createArrayNode();
        for (JsonNode chunk : chunks) {
            String chunkId = chunk.path("chunkId").asText("");
            if (chunkId.isBlank()) {
                continue;
            }
            var source = mapper.createObjectNode();
            String title = chunk.path("title").asText("");
            source.put("title", title.isBlank() ? "Knowledge" : title);
            source.put("chunkId", chunkId);
            String documentId = chunk.path("documentId").asText("");
            if (!documentId.isBlank()) {
                source.put("documentId", documentId);
            }
            if (chunk.path("score").isNumber()) {
                source.put("score", chunk.path("score").asDouble());
            }
            sources.add(source);
        }
        if (!sources.isEmpty()) {
            node.set("sources", sources);
        }
    }

    private Mono<AgentExecutionResult> agentFailed(WorkflowRunContext ctx, Throwable error) {
        String msg = error.getMessage() == null ? error.toString() : error.getMessage();
        ctx.emit(EventType.AGENT_FAILED, Map.of("agentId", AGENT_ID, "error", msg), false);
        return Mono.error(error);
    }
}
