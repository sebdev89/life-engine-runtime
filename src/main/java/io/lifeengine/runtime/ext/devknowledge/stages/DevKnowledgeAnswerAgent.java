package io.lifeengine.runtime.ext.devknowledge.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.agents.LlmAgentSupport;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.ext.devknowledge.DevKnowledgePrompts;
import io.lifeengine.runtime.llm.LlmClient;
import io.lifeengine.runtime.llm.LlmMessage;
import io.lifeengine.runtime.prompts.PromptTemplate;
import io.lifeengine.runtime.prompts.PromptTemplateRegistry;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Única etapa de {@code dev.knowledge-answer.v1}: responde una pregunta sobre una base de código a
 * partir de los fragmentos que Dev Agent ya recuperó de RAG.
 *
 * <h2>El contrato de salida es de Dev Agent, no de acá</h2>
 *
 * <p>{@code DevKnowledgeAnswerParser} del lado de Dev Agent busca la etapa cuyo {@code stageId} sea
 * exactamente {@code dev-answer} y exige un JSON con {@code answer} y {@code confidence} no vacíos;
 * si falta cualquiera de los dos, descarta la salida y el run se reporta como fallido. Por eso la
 * respuesta del modelo se normaliza acá antes de publicarla, en vez de confiar en que salga perfecta.
 *
 * <h2>Las citas se filtran contra la entrada</h2>
 *
 * <p>Un modelo puede citar un {@code chunkId} que no estaba en los fragmentos. Esa cita es
 * irrastreable y hace que una respuesta inventada parezca fundamentada — precisamente la confusión
 * que este workflow tiene que evitar. Se descarta toda fuente cuyo {@code chunkId} no venga en la
 * entrada.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.dev-knowledge.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DevKnowledgeAnswerAgent implements AgentExecutor {

    public static final String AGENT_ID = "dev-knowledge-answer-agent";

    private static final Set<String> ALLOWED_CONFIDENCE = Set.of("HIGH", "MEDIUM", "LOW");

    private final LlmClient llmClient;
    private final ObjectMapper mapper;
    private final PromptTemplateRegistry promptTemplateRegistry;

    public DevKnowledgeAnswerAgent(
            @Qualifier("chatLlmClient") LlmClient llmClient,
            ObjectMapper mapper,
            PromptTemplateRegistry promptTemplateRegistry) {
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

        // La entrada del workflow es la fuente: la arma Dev Agent con la pregunta y los chunks. El
        // input de la etapa está vacío porque es la primera.
        String workflowInput = ctx.input() == null || ctx.input().isBlank() ? "{}" : ctx.input();

        JsonNode source;
        try {
            source = mapper.readTree(workflowInput);
        } catch (Exception e) {
            return agentFailed(ctx, e);
        }

        Set<String> knownChunkIds = chunkIds(source);

        PromptTemplate template =
                promptTemplateRegistry.require(DevKnowledgePrompts.ANSWER_ID, DevKnowledgePrompts.VERSION_V1);
        List<LlmMessage> messages =
                List.of(new LlmMessage("system", template.systemMessage()), new LlmMessage("user", workflowInput));

        return LlmAgentSupport.callLlm(ctx, request.stageId(), AGENT_ID, llmClient, messages, template)
                .flatMap(
                        response -> {
                            String canonical;
                            try {
                                canonical = normalize(response.content(), knownChunkIds);
                            } catch (Exception e) {
                                return agentFailed(ctx, e);
                            }
                            ctx.putAgentOutput(AGENT_ID, canonical);

                            Map<String, String> attrs = new LinkedHashMap<>();
                            attrs.put("agentId", AGENT_ID);
                            attrs.put("chunksProvided", Integer.toString(knownChunkIds.size()));
                            attrs.put("output", WorkflowRunContext.truncate(canonical, 500));
                            ctx.emit(EventType.AGENT_SUCCEEDED, attrs, false);

                            return Mono.just(AgentExecutionResult.ok(AGENT_ID, canonical));
                        })
                .onErrorResume(error -> agentFailed(ctx, error));
    }

    /** Los {@code chunkId} que de verdad llegaron, para poder descartar citas inventadas. */
    private Set<String> chunkIds(JsonNode source) {
        Set<String> ids = new LinkedHashSet<>();
        JsonNode chunks = source.path("knowledgeContext").path("retrievedChunks");
        if (chunks.isArray()) {
            for (JsonNode chunk : chunks) {
                String id = chunk.path("chunkId").asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /**
     * Deja la respuesta del modelo en la forma que exige Dev Agent.
     *
     * <p>Tolera un cerco de markdown porque los modelos locales lo agregan a pesar de la
     * instrucción, y perder una respuesta correcta por tres backticks sería absurdo. Lo que NO se
     * tolera es una respuesta vacía o una cita no rastreable.
     */
    public String normalize(String raw, Set<String> knownChunkIds) throws Exception {
        JsonNode parsed = mapper.readTree(stripFences(raw));

        String answer = parsed.path("answer").asText("").trim();
        if (answer.isBlank()) {
            // Sin `answer` el parser de Dev Agent descarta la etapa entera y el run se ve como
            // fallido sin decir por qué. Falla acá, donde el motivo todavía se puede explicar.
            throw new IllegalArgumentException(AGENT_ID + ": el modelo no devolvió 'answer'");
        }

        String confidence = parsed.path("confidence").asText("").trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_CONFIDENCE.contains(confidence)) {
            // Un valor fuera del enum no invalida la respuesta; se degrada a LOW, que es la lectura
            // conservadora y honesta de "no sabemos cuánto confiar".
            confidence = "LOW";
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("answer", answer);
        out.put("confidence", confidence);

        ArrayNode sources = mapper.createArrayNode();
        JsonNode rawSources = parsed.get("sources");
        if (rawSources != null && rawSources.isArray()) {
            for (JsonNode item : rawSources) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                String chunkId = item.path("chunkId").asText("").trim();
                // El filtro es lo que hace que una cita signifique algo.
                if (chunkId.isBlank() || !knownChunkIds.contains(chunkId)) {
                    continue;
                }
                String title = item.path("title").asText("").trim();
                ObjectNode source = mapper.createObjectNode();
                // `title` en blanco haría que el parser de Dev Agent descarte la fuente.
                source.put("title", title.isBlank() ? "Knowledge" : title);
                source.put("chunkId", chunkId);
                String documentId = item.path("documentId").asText("").trim();
                if (!documentId.isBlank()) {
                    source.put("documentId", documentId);
                }
                if (item.path("score").isNumber()) {
                    source.put("score", item.path("score").asDouble());
                }
                sources.add(source);
            }
        }
        out.set("sources", sources);
        return mapper.writeValueAsString(out);
    }

    /** Quita un cerco ```/```json alrededor del JSON, si el modelo lo agregó. */
    private static String stripFences(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstBreak = text.indexOf('\n');
        if (firstBreak < 0) {
            return text;
        }
        String body = text.substring(firstBreak + 1);
        int closing = body.lastIndexOf("```");
        return (closing < 0 ? body : body.substring(0, closing)).trim();
    }

    private Mono<AgentExecutionResult> agentFailed(WorkflowRunContext ctx, Throwable error) {
        String msg = error.getMessage() == null ? error.toString() : error.getMessage();
        ctx.emit(EventType.AGENT_FAILED, Map.of("agentId", AGENT_ID, "error", msg), false);
        return Mono.error(error);
    }
}
