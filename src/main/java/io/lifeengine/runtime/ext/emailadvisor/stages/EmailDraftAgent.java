package io.lifeengine.runtime.ext.emailadvisor.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.agents.LlmAgentSupport;
import io.lifeengine.runtime.agents.StrictAgentJson;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.ext.emailadvisor.EmailDraftPrompts;
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
 * Redacta un borrador de respuesta a un correo (KAN-236).
 *
 * <p>Usa el rol <b>{@code chat}</b>, no {@code fast}. El triage clasifica —una etiqueta, donde
 * importa la latencia— pero acá el producto <em>es</em> la prosa: un borrador que una persona va a
 * leer y aprobar. Es la misma razón por la que {@code business-chat.reply.v1} usa {@code chat}.
 *
 * <p>El agente <b>no decide nada de negocio</b>. No sabe a qué correos se les contesta ni con qué
 * prioridad: recibe un correo que Business Chat ya decidió que amerita borrador, y devuelve texto.
 *
 * <p>La validación es estructural, no de contenido: campos presentes, tipos correctos y longitudes
 * dentro de lo que la base acepta. Que el borrador no invente datos se pide en el prompt y lo
 * verifica la persona que aprueba — un validador no puede distinguir un monto inventado de uno
 * real, y pretender que sí sería peor que no validar, porque daría una garantía falsa.
 */
@Component
public class EmailDraftAgent implements AgentExecutor {

    public static final String AGENT_ID = "email-draft-agent";

    /** Límite de {@code email_drafts.subject}, alineado con el de {@code email_messages}. */
    static final int MAX_SUBJECT_CHARS = 998;

    /**
     * Techo del cuerpo. No es un límite de la base sino de sensatez: un borrador de respuesta más
     * largo que esto casi siempre es el modelo divagando, y conviene que falle y se reintente antes
     * de que una persona tenga que leerlo para descubrirlo.
     */
    static final int MAX_BODY_CHARS = 4000;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EmailDraftAgent(@Qualifier("chatLlmClient") LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public Set<String> capabilities() {
        return Set.of("execute", "llm", "structured-output", "compose");
    }

    @Override
    public Mono<AgentExecutionResult> execute(AgentExecutionRequest request, WorkflowRunContext ctx) {
        if (ctx.isCancelled()) {
            return Mono.error(new IllegalStateException("Run cancelled"));
        }
        ctx.emit(EventType.AGENT_STARTED, Map.of("agentId", AGENT_ID), false);

        List<LlmMessage> messages =
                List.of(
                        new LlmMessage("system", EmailDraftPrompts.system()),
                        new LlmMessage("user", request.input()));

        return LlmAgentSupport.callLlm(ctx, request.stageId(), AGENT_ID, llmClient, messages)
                .flatMap(
                        response -> {
                            try {
                                String canonical = validate(response.content());
                                Map<String, String> completed = new LinkedHashMap<>();
                                completed.put("agentId", AGENT_ID);
                                completed.put("promptVersion", EmailDraftPrompts.VERSION);
                                // Nunca se emite el cuerpo del borrador ni el del correo: son datos
                                // personales y este evento queda en la traza del run. Sólo su tamaño.
                                completed.put("bodyChars", String.valueOf(bodyLength(canonical)));
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
     * @throws IllegalArgumentException si falta un campo, si viene vacío o si excede el techo
     */
    String validate(String raw) {
        JsonNode node;
        try {
            // Igual que en el triage: el modelo envuelve el JSON en un cerco ```json aunque el
            // prompt lo prohíba. StrictAgentJson lo tolera sin aflojar la gramática.
            node = objectMapper.readTree(StrictAgentJson.canonicalJson(raw));
        } catch (Exception e) {
            throw new IllegalArgumentException("la respuesta no es JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("la respuesta no es un objeto JSON");
        }

        String subject = text(node, "subject", MAX_SUBJECT_CHARS);
        String body = text(node, "body", MAX_BODY_CHARS);

        double confidence = node.path("confidence").asDouble(-1);
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence fuera de [0,1]: " + confidence);
        }

        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("subject", subject);
        canonical.put("body", body);
        canonical.put("confidence", confidence);
        canonical.put("reason", node.path("reason").asText(""));
        canonical.put("promptVersion", EmailDraftPrompts.VERSION);

        try {
            return objectMapper.writeValueAsString(canonical);
        } catch (Exception e) {
            throw new IllegalArgumentException("no se pudo canonicalizar la salida", e);
        }
    }

    private String text(JsonNode node, String field, int maxChars) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("falta el campo '" + field + "'");
        }
        String text = value.asText().strip();
        if (text.length() > maxChars) {
            throw new IllegalArgumentException(
                    "'" + field + "' excede " + maxChars + " caracteres: " + text.length());
        }
        return text;
    }

    private int bodyLength(String canonicalJson) {
        try {
            return objectMapper.readTree(canonicalJson).path("body").asText("").length();
        } catch (Exception e) {
            return -1;
        }
    }
}
