package io.lifeengine.runtime.ext.businesschat.intelligence;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic, rule-based detector that classifies each inbound Business Chat message into a
 * {@link KnowledgeNeedStrategy} without calling an LLM.
 *
 * <p>Detection priority:
 * <ol>
 *   <li>Blank message or {@code human_handoff} intent → {@code NONE}
 *   <li>Pure greeting / acknowledgement / farewell → {@code NONE}
 *   <li>Has both RAG + SEARCH signals → {@code HYBRID}
 *   <li>Has SEARCH signals only → {@code SEARCH_ONLY}
 *   <li>Has RAG signals only → {@code RAG_ONLY}
 *   <li>Long message with no explicit signals → {@code RAG_ONLY} (safe default)
 *   <li>Short message with no signals → {@code NONE}
 * </ol>
 *
 * <p>{@code ragQuery} and {@code searchQuery} default to the original message when the
 * corresponding strategy requires retrieval. More precise query extraction is deferred to
 * a future sprint.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.business-chat.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BusinessKnowledgeNeedDetector {

    // ── NONE: pure greetings ───────────────────────────────────────────────────
    private static final Set<String> GREETING_SET = Set.of(
            "hola", "hi", "hello", "hey", "buenas", "buen dia", "buen día",
            "buenos dias", "buenos días", "buenas tardes", "buenas noches",
            "good morning", "good afternoon", "good evening", "good night");

    // ── NONE: acknowledgements, confirmations, farewells ──────────────────────
    private static final Set<String> ACK_SET = Set.of(
            "ok", "okay", "okey", "gracias", "muchas gracias", "mil gracias",
            "thanks", "thank you", "thank you very much",
            "perfecto", "perfecto gracias", "entendido", "entendido gracias",
            "confirmo", "confirmado", "afirmativo", "de acuerdo", "esta bien",
            "está bien", "claro", "claro que si", "claro que sí", "por supuesto",
            "dale", "listo", "genial", "excelente", "bien", "todo bien",
            "si", "sí", "no", "no gracias", "no, gracias",
            "chau", "bye", "adios", "adiós", "hasta luego", "hasta pronto",
            "nos vemos", "ciao", "eso es todo", "nada mas", "nada más",
            "eso nada mas", "eso nada más", "ok entendido", "ok, entendido",
            "ya entendi", "ya entendí", "ya vi", "ya lo vi", "ok gracias", "ok, gracias");

    // ── NONE: human handoff phrases ───────────────────────────────────────────
    private static final Set<String> HANDOFF_PHRASES = Set.of(
            "quiero hablar con una persona", "quiero hablar con alguien",
            "quiero un humano", "necesito un humano", "un agente humano",
            "quiero un agente", "necesito un agente", "necesito un operador",
            "quiero un operador", "hablar con alguien", "hablar con una persona real",
            "con un humano", "con una persona real");

    // ── SEARCH signals: real-time, external, market ───────────────────────────
    private static final List<Pattern> SEARCH_SIGNALS = List.of(
            // Temporal: today / now / current / recent / latest (including plurals)
            Pattern.compile(
                    "\\b(hoy|ahora|actual|actualmente|esta semana|este mes|este a[ñn]o|"
                            + "recientemente|recientes?|[uú]ltim[ao]s?|"
                            + "m[aá]s recientes?|novedades|novedad)\\b",
                    Pattern.CASE_INSENSITIVE),
            // Financial / market
            Pattern.compile(
                    "\\b(d[oó]lar|euro|bitcoin|crypto|criptomoneda|cotizaci[oó]n|"
                            + "bolsa|acciones|inflaci[oó]n|tasa de inter[eé]s|"
                            + "precio del mercado|valor de mercado|mercado financiero)\\b",
                    Pattern.CASE_INSENSITIVE),
            // Legal / regulatory changes (including conjugations: cambió, cambiaron, etc.)
            Pattern.compile(
                    "\\b(nueva ley|nuevo decreto|reforma[s]? laborales?|"
                            + "cambi[oó] la ley|cambiaron la ley|cambi\\w{0,4} la ley|"
                            + "cambio de ley|nueva normativa|normativa actual|"
                            + "reforma[s]? recientes?|decreto nuevo|reglamento nuevo|"
                            + "cambios? legales?|cambio legal|ley nueva|leyes nuevas)\\b",
                    Pattern.CASE_INSENSITIVE),
            // News
            Pattern.compile(
                    "\\b(noticias?|news|[uú]ltimas noticias|"
                            + "informaci[oó]n actual|datos actuales)\\b",
                    Pattern.CASE_INSENSITIVE));

    // ── RAG signals: internal knowledge, business inquiry ─────────────────────
    // Note: generic question marks and question words (¿, cómo, qué, cuál…) are intentionally
    // excluded — they appear in both RAG and SEARCH contexts and produce false HYBRID results.
    private static final List<Pattern> RAG_SIGNALS = List.of(
            // Pricing / cost — business-specific
            Pattern.compile(
                    "\\b(precio|precios|costo|costos|vale|cobran?|cuesta|cu[aá]nto|sale|"
                            + "tarifa|tarifas|presupuesto)\\b",
                    Pattern.CASE_INSENSITIVE),
            // Schedule / location
            Pattern.compile(
                    "\\b(horario|horarios|atienden|abren|cierran|turno|turnos|cita|citas|"
                            + "direcci[oó]n|ubicaci[oó]n|ubicados?|"
                            + "como llego|c[oó]mo llego|sucursal|sede|oficina)\\b",
                    Pattern.CASE_INSENSITIVE),
            // Services / products
            Pattern.compile(
                    "\\b(servicio|servicios|producto|productos|ofrecen|tienen|disponen|"
                            + "garant[ií]a|pol[ií]tica|condiciones|contrato|t[eé]rminos|"
                            + "descuento|descuentos|promoci[oó]n|oferta|ofertas|incluye|incluyen)\\b",
                    Pattern.CASE_INSENSITIVE),
            // Professional / legal / support inquiry
            Pattern.compile(
                    "\\b(caso|casos|asesor|asesoramiento|consulta|consultas|"
                            + "despido|laboral|indemnizaci[oó]n|abogado|estudio jur[ií]dico|"
                            + "cl[ií]nica|m[eé]dico|m[eé]dica|doctor|consulta m[eé]dica)\\b",
                    Pattern.CASE_INSENSITIVE),
            // Shipping / delivery
            Pattern.compile(
                    "\\b(env[ií]an|env[ií]o|env[ií]os|entregan|despachan|delivery|"
                            + "despacho|enviar|llega|demora|plazo)\\b",
                    Pattern.CASE_INSENSITIVE));

    // ── Minimum message length to infer RAG as safe default ───────────────────
    private static final int LONG_MESSAGE_THRESHOLD = 30;

    public KnowledgeNeedDetection detect(DetectionRequest request) {
        if (request == null || isBlank(request.message())) {
            return KnowledgeNeedDetection.none("empty_message");
        }

        // 1. Intent shortcuts
        if ("human_handoff".equals(request.intent())) {
            return KnowledgeNeedDetection.none("human_handoff_intent");
        }
        if ("greeting".equals(request.intent())) {
            return KnowledgeNeedDetection.none("greeting_intent");
        }

        String message = request.message().trim();
        String normalized = normalize(message);

        // 2. Exact-match NONE sets
        if (GREETING_SET.contains(normalized)
                || ACK_SET.contains(normalized)
                || HANDOFF_PHRASES.contains(normalized)) {
            return KnowledgeNeedDetection.none("greeting_or_acknowledgement");
        }

        // 3. Detect signals (use original message for regex to preserve accents/casing)
        boolean hasSearch = matches(SEARCH_SIGNALS, message);
        boolean hasRag = matches(RAG_SIGNALS, message);

        // 4. leadFacts context refinement: active case → always lean toward RAG
        boolean hasActiveLead = hasActiveLead(request);
        if (hasActiveLead && !hasSearch) {
            hasRag = true;
        }

        // 5. Combine
        if (hasSearch && hasRag) {
            return new KnowledgeNeedDetection(
                    KnowledgeNeedStrategy.HYBRID,
                    "internal_and_external_signals",
                    message,
                    message);
        }
        if (hasSearch) {
            return new KnowledgeNeedDetection(
                    KnowledgeNeedStrategy.SEARCH_ONLY,
                    "external_realtime_signal",
                    "",
                    message);
        }
        if (hasRag) {
            return new KnowledgeNeedDetection(
                    KnowledgeNeedStrategy.RAG_ONLY,
                    "internal_knowledge_signal",
                    message,
                    "");
        }

        // 6. No signals: long message → RAG_ONLY default; short → NONE
        if (message.length() >= LONG_MESSAGE_THRESHOLD) {
            return new KnowledgeNeedDetection(
                    KnowledgeNeedStrategy.RAG_ONLY,
                    "long_message_default",
                    message,
                    "");
        }
        return KnowledgeNeedDetection.none("no_knowledge_signals");
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private static String normalize(String text) {
        return text.toLowerCase()
                .replaceAll("[!¡?¿.,;:]", "")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean matches(List<Pattern> patterns, String text) {
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) return true;
        }
        return false;
    }

    private static boolean hasActiveLead(DetectionRequest request) {
        if (request.leadFacts() == null || request.leadFacts().isEmpty()) return false;
        return request.leadFacts().containsKey("caseType")
                || request.leadFacts().containsKey("handoffRequired");
    }
}
