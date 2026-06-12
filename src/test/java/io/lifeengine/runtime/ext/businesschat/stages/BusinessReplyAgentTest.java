package io.lifeengine.runtime.ext.businesschat.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Focused unit tests for {@link BusinessReplyAgent#buildReplyUserInput(ObjectMapper, String, String)}.
 *
 * <p>The reply LLM was tipping over the local 4096-token vLLM ceiling
 * ({@code 3905 prompt + 192 output = 4097 → HTTP 400 → llm_failure_fallback}) because
 * BusinessReplyAgent was shipping the full {@code ctx.input()} as {@code source} on top
 * of {@code businessContext}, duplicating ~1400 tokens of {@code businessContext},
 * {@code botProfile}, {@code conversationHistory} and other fields the context-agent
 * had already canonicalized. These tests pin the post-fix contract: only the source
 * fields the reply system prompt actually cites on {@code source.*} survive, everything
 * else is dropped and re-read from {@code businessContext.*}.
 */
class BusinessReplyAgentTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void buildReplyUserInput_shipsOnlySourceFieldsReferencedBySystemPrompt() throws Exception {
        String sourceJson =
                """
                {
                  "channel": "WEB_CHAT",
                  "message": "Necesito hablar con un abogado",
                  "customer": {"name": "Smoke Patched", "externalId": "ext-1"},
                  "botId": "bogabot",
                  "tenantId": "local-demo",
                  "conversationId": "b158decd-9c6b-41aa-92c5-8ae49501c862",
                  "conversationHistory": [
                    {"customerMessage": "sufrí un despido",
                     "botResponse": "Lamento mucho lo que pasó. ¿Querés agendar una consulta inicial?"}
                  ],
                  "botProfile": {
                    "personality": "Profesional, empático.",
                    "rules": ["No prometer resultados."]
                  },
                  "businessContext": {
                    "businessName": "BogaBot — Estudio Jurídico",
                    "rules": ["Memoria de sesión: la consulta inicial."],
                    "faqs": [{"question": "¿Atienden despidos?", "answer": "Sí."}],
                    "catalogItems": [{"name": "Consulta inicial", "priceText": "ARS 45.000"}]
                  },
                  "now": "2026-06-12T09:00:00Z",
                  "locale": "es-AR",
                  "replyMaxTokens": 192,
                  "channelContext": {"thread": "ABC"}
                }
                """;
        String businessContextJson =
                """
                {
                  "channel": "WEB_CHAT",
                  "botId": "bogabot",
                  "conversationId": "b158decd-9c6b-41aa-92c5-8ae49501c862",
                  "message": "Necesito hablar con un abogado",
                  "conversationHistory": [
                    {"customerMessage": "sufrí un despido", "botResponse": "Lamento mucho..."}
                  ],
                  "botProfile": {"personality": "Profesional, empático."},
                  "intent": "human_handoff",
                  "confidence": "HIGH",
                  "handoffRequired": true,
                  "leadCaptured": false,
                  "contextNotes": "Customer is asking for a lawyer.",
                  "knowledgeBase": "Negocio: BogaBot — Estudio Jurídico\\nCatálogo: ...",
                  "businessName": "BogaBot — Estudio Jurídico",
                  "tone": "Profesional y cercano."
                }
                """;

        String userInput =
                BusinessReplyAgent.buildReplyUserInput(mapper, sourceJson, businessContextJson);
        JsonNode root = mapper.readTree(userInput);

        Assertions.assertThat(root.has("source")).isTrue();
        Assertions.assertThat(root.has("businessContext")).isTrue();

        JsonNode source = root.get("source");

        // Required by REPLY_SYSTEM_PROMPT Hard rules: must keep source.message,
        // source.channel and source.customer.
        Assertions.assertThat(source.path("message").asText())
                .isEqualTo("Necesito hablar con un abogado");
        Assertions.assertThat(source.path("channel").asText()).isEqualTo("WEB_CHAT");
        Assertions.assertThat(source.path("customer").path("name").asText())
                .isEqualTo("Smoke Patched");

        // Must be dropped (already duplicated inside businessContext or unused by prompt):
        // source.businessContext, source.botProfile, source.conversationHistory,
        // source.botId, source.tenantId, source.conversationId, source.now, source.locale,
        // source.replyMaxTokens, source.channelContext.
        Assertions.assertThat(source.has("businessContext"))
                .as("source.businessContext must not be shipped — duplicates businessContext.*")
                .isFalse();
        Assertions.assertThat(source.has("botProfile"))
                .as("source.botProfile must not be shipped — duplicates businessContext.botProfile")
                .isFalse();
        Assertions.assertThat(source.has("conversationHistory"))
                .as(
                        "source.conversationHistory must not be shipped — duplicates "
                                + "businessContext.conversationHistory")
                .isFalse();
        Assertions.assertThat(source.has("botId")).isFalse();
        Assertions.assertThat(source.has("tenantId")).isFalse();
        Assertions.assertThat(source.has("conversationId")).isFalse();
        Assertions.assertThat(source.has("now")).isFalse();
        Assertions.assertThat(source.has("locale")).isFalse();
        Assertions.assertThat(source.has("replyMaxTokens")).isFalse();
        Assertions.assertThat(source.has("channelContext")).isFalse();

        // businessContext passes through untouched — it carries knowledgeBase, intent,
        // handoff metadata and tone which the reply LLM uses as the authoritative
        // source of business knowledge.
        JsonNode bc = root.get("businessContext");
        Assertions.assertThat(bc.path("knowledgeBase").asText()).startsWith("Negocio:");
        Assertions.assertThat(bc.path("intent").asText()).isEqualTo("human_handoff");
        Assertions.assertThat(bc.path("handoffRequired").asBoolean()).isTrue();
        Assertions.assertThat(bc.path("tone").asText()).isEqualTo("Profesional y cercano.");
    }

    @Test
    void buildReplyUserInput_dropsCustomerWhenAbsent() throws Exception {
        String sourceJson = "{\"channel\":\"WEB_CHAT\",\"message\":\"hola\"}";
        String businessContextJson = "{\"intent\":\"greeting\"}";

        String userInput =
                BusinessReplyAgent.buildReplyUserInput(mapper, sourceJson, businessContextJson);
        JsonNode source = mapper.readTree(userInput).get("source");

        Assertions.assertThat(source.has("customer")).isFalse();
        Assertions.assertThat(source.path("channel").asText()).isEqualTo("WEB_CHAT");
        Assertions.assertThat(source.path("message").asText()).isEqualTo("hola");
    }

    @Test
    void buildReplyUserInput_handlesBlankInputsGracefully() throws Exception {
        String userInput = BusinessReplyAgent.buildReplyUserInput(mapper, "", "");
        JsonNode root = mapper.readTree(userInput);

        Assertions.assertThat(root.has("source")).isTrue();
        Assertions.assertThat(root.has("businessContext")).isTrue();
        Assertions.assertThat(root.get("source").size()).isZero();
        Assertions.assertThat(root.get("businessContext").size()).isZero();
    }

    @Test
    void buildReplyUserInput_yieldsMaterialTokenSavingsVsUnprunedPayload() throws Exception {
        // Realistic BogaBot 2nd-turn payload (legal demo) — ctx.input() carries the
        // full enriched businessContext + history + botProfile + conversation metadata.
        String sourceJson = buildRealisticSourcePayload();
        String businessContextJson = buildRealisticBusinessContext();

        // Unpruned baseline: what the agent used to ship.
        ObjectMapper m = mapper;
        var unprunedCombined = m.createObjectNode();
        unprunedCombined.set("source", m.readTree(sourceJson));
        unprunedCombined.set("businessContext", m.readTree(businessContextJson));
        String unpruned = m.writeValueAsString(unprunedCombined);

        // Pruned: what the patched agent now ships.
        String pruned = BusinessReplyAgent.buildReplyUserInput(m, sourceJson, businessContextJson);

        int saved = unpruned.length() - pruned.length();
        // The pruning must remove a significant slice of the prompt — at ~3 chars/token
        // for accented Spanish + JSON the 4096-token ceiling demands several hundred
        // tokens of headroom on the reply call. 2000 chars (~650 vLLM tokens for accented
        // JSON) is the conservative floor that discriminates the fix from the regression
        // (un-pruned baseline saves 0; a regression that re-introduces source.botProfile
        // alone would drop savings below this threshold).
        Assertions.assertThat(saved)
                .as(
                        "pruning the duplicated source.* fields must remove ≥2000 chars of "
                                + "user-message JSON so the reply LLM stays well under the "
                                + "4096-token context window")
                .isGreaterThanOrEqualTo(2000);
        // Spot-check the source sub-tree explicitly: botProfile / conversationHistory must
        // not be re-emitted there (they live in businessContext.* in the pruned payload).
        JsonNode prunedSource = mapper.readTree(pruned).get("source");
        Assertions.assertThat(prunedSource.has("botProfile")).isFalse();
        Assertions.assertThat(prunedSource.has("conversationHistory")).isFalse();
        Assertions.assertThat(prunedSource.has("businessContext")).isFalse();
    }

    private static String buildRealisticSourcePayload() {
        // Shape mirrors what business-chat-service ships post-ReplyPayloadCompactor on
        // turn 2 of the smoke scenario: a compacted businessContext (~1200 tokens), a
        // botProfile (~200 tokens), a conversationHistory entry, plus the standard
        // workflow envelope fields.
        return """
                {
                  "channel": "WEB_CHAT",
                  "botId": "bogabot",
                  "tenantId": "local-demo",
                  "conversationId": "b158decd-9c6b-41aa-92c5-8ae49501c862",
                  "message": "Necesito hablar con un abogado",
                  "customer": {"name": "Smoke Patched"},
                  "now": "2026-06-12T09:51:00Z",
                  "locale": "es-AR",
                  "replyMaxTokens": 192,
                  "channelContext": {"thread": "web-chat"},
                  "conversation": {
                    "id": "b158decd-9c6b-41aa-92c5-8ae49501c862",
                    "status": "OPEN",
                    "startedAt": "2026-06-12T09:51:00Z"
                  },
                  "conversationHistory": [
                    {"customerMessage": "sufrí un despido",
                     "botResponse": "Lo siento mucho. Para asesorarte bien necesito ver el telegrama y los recibos. ¿Querés agendar una consulta inicial de 45 minutos?"}
                  ],
                  "botProfile": {
                    "personality": "Asistente virtual del estudio jurídico. Profesional, empático y claro. No reemplaza al abogado.",
                    "greetingStyle": "Breve y formal en el primer mensaje; usa el nombre si está disponible.",
                    "rules": [
                      "No prometer resultados.",
                      "No dar consejo legal vinculante.",
                      "Derivar casos complejos."
                    ]
                  },
                  "businessContext": {
                    "businessName": "BogaBot — Estudio Jurídico",
                    "industry": "LEGAL",
                    "tone": "Profesional y cercano, estilo WhatsApp.",
                    "sessionTopic": "consultation",
                    "sessionAnchor": "la consulta inicial",
                    "rules": [
                      "Aclarar siempre que la respuesta es informativa.",
                      "No interpretar leyes para casos concretos sin revisión humana.",
                      "Memoria de sesión: mantener el hilo sobre la consulta inicial."
                    ],
                    "faqs": [
                      {"question": "¿Cómo es la primera consulta?",
                       "answer": "Reunión de 30 minutos con un abogado, presencial u online."},
                      {"question": "¿Cuánto cuesta la consulta inicial?",
                       "answer": "Desde ARS 45.000; se acredita si avanzás con el caso."},
                      {"question": "¿Atienden fuera de horario?",
                       "answer": "El bot responde 24/7. Un abogado responde en horario de oficina."},
                      {"question": "¿Puedo enviar documentos por acá?",
                       "answer": "Para revisión necesitás una consulta con un abogado."}
                    ],
                    "catalogItems": [
                      {"name": "Consulta inicial laboral",
                       "description": "Revisión preliminar de despido, liquidación y accidentes.",
                       "priceText": "ARS 45.000"},
                      {"name": "Consulta inicial familia",
                       "description": "Orientación sobre divorcio, régimen de visitas y acuerdos.",
                       "priceText": "ARS 50.000"},
                      {"name": "Consulta inicial sucesiones",
                       "description": "Trámites sucesorios y declaratoria de herederos.",
                       "priceText": "ARS 55.000"}
                    ]
                  }
                }
                """;
    }

    private static String buildRealisticBusinessContext() {
        return """
                {
                  "channel": "WEB_CHAT",
                  "botId": "bogabot",
                  "conversationId": "b158decd-9c6b-41aa-92c5-8ae49501c862",
                  "message": "Necesito hablar con un abogado",
                  "conversationHistory": [
                    {"customerMessage": "sufrí un despido",
                     "botResponse": "Lo siento mucho. ¿Querés agendar una consulta inicial?"}
                  ],
                  "botProfile": {
                    "personality": "Asistente virtual del estudio jurídico.",
                    "rules": ["No prometer resultados."]
                  },
                  "intent": "human_handoff",
                  "confidence": "HIGH",
                  "confidenceReason": "pide_humano",
                  "handoffRequired": true,
                  "leadCaptured": false,
                  "contextNotes": "Customer is asking for a lawyer after dismissal.",
                  "knowledgeBase": "Negocio: BogaBot — Estudio Jurídico\\nCatálogo:\\n- Consulta inicial laboral (ARS 45.000): Revisión preliminar de despido, liquidación y accidentes.\\n- Consulta inicial familia (ARS 50.000): Orientación sobre divorcio.\\nPreguntas frecuentes:\\n- ¿Cómo es la primera consulta? Reunión de 30 minutos con un abogado.\\n- ¿Cuánto cuesta? Desde ARS 45.000.",
                  "businessName": "BogaBot — Estudio Jurídico",
                  "tone": "Profesional y cercano, estilo WhatsApp."
                }
                """;
    }
}
