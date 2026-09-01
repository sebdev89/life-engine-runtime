package io.lifeengine.runtime.ext.emailadvisor.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.agents.LlmAgentSupport;
import io.lifeengine.runtime.agents.StrictAgentJson;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.ext.emailadvisor.EmailTriagePrompts;
import io.lifeengine.runtime.llm.LlmClient;
import io.lifeengine.runtime.llm.LlmMessage;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Clasifica un correo (KAN-234).
 *
 * <p>Usa el rol <b>{@code fast}</b>, que en este runtime está descrito como el rol de
 * "clasificación/extracción, donde importa la latencia y no la prosa". Es exactamente esta tarea, y
 * evita tocar {@code RuntimeLlmRolesProperties} para inventar un rol nuevo — ese record declara
 * {@code chat} y {@code fast} como campos fijos, así que agregar un tercero sería modificar el
 * Runtime más allá de un workflow aditivo.
 *
 * <p>El agente <b>no decide nada de negocio</b>. No sabe qué significa HIGH ni cuándo un correo de
 * la obra social es urgente: sólo ejecuta la inferencia y devuelve JSON validado. La semántica es de
 * Business Chat. Si esa línea se cruza, la próxima categoría nueva habría que agregarla en dos
 * repositorios.
 *
 * <p>La validación es estricta a propósito: un JSON con una categoría inventada se rechaza en vez
 * de propagarse. Business Chat trata ese rechazo como fallo técnico —no persiste clasificación y
 * reintenta— y no como "el modelo dijo que no sabe", que es una respuesta legítima y distinta.
 */
@Component
public class EmailTriageAgent implements AgentExecutor {

    public static final String AGENT_ID = "email-triage-agent";

    /** Vocabulario congelado en el CHECK de {@code email_classifications} (V27). */
    private static final Set<String> CATEGORIES =
            Set.of(
                    "JOB_OPPORTUNITY",
                    "RECRUITER",
                    "INTERVIEW",
                    "COMPANY",
                    "HEALTH_INSURANCE",
                    "BANK_OR_FINANCE",
                    "PROVIDER",
                    "ADMINISTRATIVE",
                    "NEWSLETTER",
                    "PERSONAL",
                    "SPAM_OR_LOW_PRIORITY",
                    "UNCLASSIFIED");

    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EmailTriageAgent(@Qualifier("fastLlmClient") LlmClient llmClient) {
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
                List.of(
                        new LlmMessage("system", EmailTriagePrompts.system()),
                        new LlmMessage("user", request.input()));

        return LlmAgentSupport.callLlm(ctx, request.stageId(), AGENT_ID, llmClient, messages)
                .flatMap(
                        response -> {
                            try {
                                String canonical = validate(response.content());
                                Map<String, String> completed = new LinkedHashMap<>();
                                completed.put("agentId", AGENT_ID);
                                completed.put("promptVersion", EmailTriagePrompts.VERSION);
                                // Nunca se emite el cuerpo del correo ni el prompt: son datos
                                // personales y este evento queda en la traza del run.
                                completed.put("structured", WorkflowRunContext.truncate(canonical, 800));
                                ctx.emit(EventType.AGENT_SUCCEEDED, completed, false);
                                return Mono.just(AgentExecutionResult.ok(AGENT_ID, canonical));
                            } catch (IllegalArgumentException e) {
                                String msg = AGENT_ID + ": " + e.getMessage();
                                ctx.emit(
                                        EventType.AGENT_FAILED,
                                        Map.of("agentId", AGENT_ID, "error", msg),
                                        false);
                                return Mono.error(new IllegalArgumentException(msg, e));
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

    /**
     * Valida el JSON del modelo y lo devuelve canonicalizado.
     *
     * @throws IllegalArgumentException si falta un campo, si una enumeración no pertenece al
     *     vocabulario congelado o si la coherencia entre banderas no se cumple
     */
    String validate(String raw) {
        JsonNode node;
        try {
            // gemma3:4b —el modelo del rol `fast`, que es el que ejecuta este agente— devuelve el
            // JSON dentro de un cerco ```json aunque el prompt lo prohíba explícitamente. Un
            // readTree directo lo rechaza y el correo termina UNCLASSIFIED sin que el modelo se
            // haya equivocado en nada. StrictAgentJson es el parser que ya usan los otros doce
            // agentes justamente para esto: tolera el cerco y el preámbulo, pero no afloja la
            // gramática (sin comas colgantes, sin comillas simples, sin coerción silenciosa).
            node = objectMapper.readTree(StrictAgentJson.canonicalJson(raw));
        } catch (Exception e) {
            throw new IllegalArgumentException("la respuesta no es JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("la respuesta no es un objeto JSON");
        }

        String category = text(node, "category");
        if (!CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("category fuera del vocabulario: " + category);
        }
        String priority = text(node, "priority");
        if (!PRIORITIES.contains(priority)) {
            throw new IllegalArgumentException("priority fuera del vocabulario: " + priority);
        }

        boolean needsReply = bool(node, "needsReply");
        boolean informationOnly = bool(node, "informationOnly");
        boolean canBeDrafted = bool(node, "canBeDrafted");

        // La implicación del contrato: informationOnly ⇒ !needsReply. La inversa NO vale — un
        // correo puede no pedir respuesta y aun así pedir una acción ("verificá tu cuenta").
        if (informationOnly && needsReply) {
            throw new IllegalArgumentException("informationOnly=true exige needsReply=false");
        }

        double confidence = node.path("confidence").asDouble(-1);
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence fuera de [0,1]: " + confidence);
        }

        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("category", category);
        canonical.put("priority", priority);
        canonical.put("needsReply", needsReply);
        canonical.put("informationOnly", informationOnly);
        canonical.put("canBeDrafted", canBeDrafted);
        canonical.put("summary", node.path("summary").asText(""));
        canonical.put("suggestedAction", node.path("suggestedAction").asText(""));
        canonical.put("confidence", confidence);
        canonical.put("reason", node.path("reason").asText(""));
        canonical.put("promptVersion", EmailTriagePrompts.VERSION);

        try {
            return objectMapper.writeValueAsString(canonical);
        } catch (Exception e) {
            throw new IllegalArgumentException("no se pudo canonicalizar la salida", e);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("falta el campo '" + field + "'");
        }
        return value.asText().trim().toUpperCase();
    }

    private boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isBoolean()) {
            throw new IllegalArgumentException("falta el booleano '" + field + "'");
        }
        return value.asBoolean();
    }
}
