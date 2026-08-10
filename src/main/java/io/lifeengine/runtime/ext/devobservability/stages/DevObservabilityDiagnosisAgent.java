package io.lifeengine.runtime.ext.devobservability.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.agents.LlmAgentSupport;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.ext.devobservability.DevObservabilityPrompts;
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
 * Única etapa de {@code dev.observability-diagnosis.v1} (KAN-256): interpreta la evidencia que Dev
 * Agent recolectó de Prometheus, Loki y Jaeger.
 *
 * <h2>Las citas se filtran contra la evidencia que trajo datos</h2>
 *
 * <p>Sólo sobreviven los ids presentes en la entrada <b>y</b> con {@code outcome=DATA}. Un modelo
 * puede citar un id inventado, o —más sutil y más peligroso— citar un ítem {@code UNAVAILABLE} para
 * sostener que "no había errores". Nadie pudo mirar ese pilar: esa cita convierte una ausencia de
 * información en una afirmación, que es exactamente el fallo que este workflow tiene que evitar.
 *
 * <p>Dev Agent vuelve a validar las citas y trunca la confianza según la cobertura. La doble
 * validación es deliberada: acá se protege el contrato de salida, y allá el invariante del negocio.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.dev-observability.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DevObservabilityDiagnosisAgent implements AgentExecutor {

    public static final String AGENT_ID = "dev-observability-diagnosis-agent";

    private static final Set<String> ALLOWED_CONFIDENCE =
            Set.of("HIGH", "MEDIUM", "LOW", "INSUFFICIENT_EVIDENCE");

    private static final String INSUFFICIENT = "INSUFFICIENT_EVIDENCE";

    private final LlmClient llmClient;
    private final ObjectMapper mapper;
    private final PromptTemplateRegistry promptTemplateRegistry;

    public DevObservabilityDiagnosisAgent(
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

        String workflowInput = ctx.input() == null || ctx.input().isBlank() ? "{}" : ctx.input();

        JsonNode source;
        try {
            source = mapper.readTree(workflowInput);
        } catch (Exception e) {
            return agentFailed(ctx, e);
        }

        Set<String> citableIds = citableEvidenceIds(source);

        PromptTemplate template = promptTemplateRegistry.require(
                DevObservabilityPrompts.DIAGNOSIS_ID, DevObservabilityPrompts.VERSION_V1);
        List<LlmMessage> messages =
                List.of(new LlmMessage("system", template.systemMessage()), new LlmMessage("user", workflowInput));

        return LlmAgentSupport.callLlm(ctx, request.stageId(), AGENT_ID, llmClient, messages, template)
                .flatMap(response -> {
                    String canonical;
                    try {
                        canonical = normalize(response.content(), citableIds);
                    } catch (Exception e) {
                        return agentFailed(ctx, e);
                    }
                    ctx.putAgentOutput(AGENT_ID, canonical);

                    Map<String, String> attrs = new LinkedHashMap<>();
                    attrs.put("agentId", AGENT_ID);
                    attrs.put("citableEvidence", Integer.toString(citableIds.size()));
                    attrs.put("output", WorkflowRunContext.truncate(canonical, 500));
                    ctx.emit(EventType.AGENT_SUCCEEDED, attrs, false);

                    return Mono.just(AgentExecutionResult.ok(AGENT_ID, canonical));
                })
                .onErrorResume(error -> agentFailed(ctx, error));
    }

    /** Los ids de evidencia que de verdad trajeron datos — los únicos que pueden sostener una causa. */
    private Set<String> citableEvidenceIds(JsonNode source) {
        Set<String> ids = new LinkedHashSet<>();
        JsonNode evidence = source.path("evidence");
        if (evidence.isArray()) {
            for (JsonNode item : evidence) {
                if (!"DATA".equals(item.path("outcome").asText(""))) {
                    continue;
                }
                String id = item.path("id").asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /**
     * Normaliza la salida del modelo al contrato que Dev Agent espera.
     *
     * <p>Si tras filtrar no queda ninguna cita válida, se fuerza {@code INSUFFICIENT_EVIDENCE} y se
     * descarta la causa. Una causa sin evidencia rastreable es una opinión: devolverla como
     * diagnóstico sería el falso verde que todo este trabajo intenta desterrar.
     */
    public String normalize(String raw, Set<String> citableIds) throws Exception {
        JsonNode parsed = mapper.readTree(stripFences(raw));

        String cause = parsed.path("probableCause").asText("").trim();
        if ("null".equalsIgnoreCase(cause)) {
            cause = "";
        }

        String confidence = parsed.path("confidence").asText("").trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_CONFIDENCE.contains(confidence)) {
            // Un valor fuera del enum se degrada a la lectura conservadora, nunca a la optimista.
            confidence = "LOW";
        }

        ArrayNode citations = mapper.createArrayNode();
        JsonNode rawCitations = parsed.get("citedEvidenceIds");
        if (rawCitations != null && rawCitations.isArray()) {
            for (JsonNode item : rawCitations) {
                String id = item.asText("").trim();
                if (!id.isBlank() && citableIds.contains(id)) {
                    citations.add(id);
                }
            }
        }

        ObjectNode out = mapper.createObjectNode();
        if (cause.isBlank() || citations.isEmpty()) {
            out.putNull("probableCause");
            out.put("confidence", INSUFFICIENT);
            out.set("citedEvidenceIds", mapper.createArrayNode());
        } else {
            out.put("probableCause", cause);
            // Se pasa tal cual, incluido INSUFFICIENT_EVIDENCE. Reescribirlo a LOW —como hacía
            // antes— era SUBIR la confianza que propuso el modelo, y §5.4 dice que sólo puede
            // bajarse. Además Dev Agent perdía el dato de que el modelo había dicho "no me alcanza".
            // Quién decide el valor final es ConfidencePolicy, del lado de Dev Agent: acá no vive
            // política de confianza, sólo la forma de la salida.
            out.put("confidence", confidence);
            out.set("citedEvidenceIds", citations);
        }
        out.put("reasoning", parsed.path("reasoning").asText("").trim());
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
