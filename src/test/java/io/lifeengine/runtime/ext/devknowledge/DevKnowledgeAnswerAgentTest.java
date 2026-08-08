package io.lifeengine.runtime.ext.devknowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.ext.devknowledge.stages.DevKnowledgeAnswerAgent;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Normalización de la respuesta del modelo (KAN-243).
 *
 * <p>Lo que se protege acá es el contrato con Dev Agent: su {@code DevKnowledgeAnswerParser} descarta
 * la etapa entera si falta {@code answer} o {@code confidence}, y en ese caso el run se reporta como
 * fallido sin decir por qué. Y una cita a un {@code chunkId} que nunca llegó hace que una respuesta
 * inventada parezca fundamentada, que es el peor resultado posible de este workflow.
 */
class DevKnowledgeAnswerAgentTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DevKnowledgeAnswerAgent agent = new DevKnowledgeAnswerAgent(null, mapper, null);

    private JsonNode normalize(String raw, Set<String> knownChunks) throws Exception {
        return mapper.readTree(agent.normalize(raw, knownChunks));
    }

    @Test
    @DisplayName("una respuesta bien formada pasa tal cual")
    void wellFormedAnswer() throws Exception {
        JsonNode out =
                normalize(
                        """
                        {"answer":"El JWT se valida en DevAgentJwtFilter.","confidence":"HIGH",
                         "sources":[{"title":"DevAgentJwtFilter.java","chunkId":"c1","documentId":"d1","score":0.91}]}
                        """,
                        Set.of("c1"));

        assertThat(out.get("answer").asText()).contains("DevAgentJwtFilter");
        assertThat(out.get("confidence").asText()).isEqualTo("HIGH");
        assertThat(out.get("sources")).hasSize(1);
        assertThat(out.get("sources").get(0).get("chunkId").asText()).isEqualTo("c1");
        assertThat(out.get("sources").get(0).get("score").asDouble()).isEqualTo(0.91);
    }

    @Test
    @DisplayName("descarta las citas a chunks que nunca llegaron")
    void dropsUntraceableCitations() throws Exception {
        JsonNode out =
                normalize(
                        """
                        {"answer":"x","confidence":"HIGH","sources":[
                          {"title":"real","chunkId":"c1"},
                          {"title":"inventado","chunkId":"no-existe"}]}
                        """,
                        Set.of("c1"));

        // La cita inventada es lo que hace pasar una respuesta sin fundamento por fundamentada.
        assertThat(out.get("sources")).hasSize(1);
        assertThat(out.get("sources").get(0).get("chunkId").asText()).isEqualTo("c1");
    }

    @Test
    @DisplayName("acepta el cerco de markdown que agregan los modelos locales")
    void toleratesMarkdownFences() throws Exception {
        JsonNode out =
                normalize(
                        "```json\n{\"answer\":\"con cerco\",\"confidence\":\"MEDIUM\"}\n```",
                        Set.of());

        assertThat(out.get("answer").asText()).isEqualTo("con cerco");
        assertThat(out.get("confidence").asText()).isEqualTo("MEDIUM");
    }

    @Test
    @DisplayName("una confidence fuera del enum se degrada a LOW, no invalida la respuesta")
    void unknownConfidenceDegradesToLow() throws Exception {
        JsonNode out = normalize("{\"answer\":\"ok\",\"confidence\":\"bastante\"}", Set.of());
        assertThat(out.get("confidence").asText()).isEqualTo("LOW");
    }

    @Test
    @DisplayName("normaliza la confidence en minúscula del modelo")
    void lowercaseConfidenceIsAccepted() throws Exception {
        JsonNode out = normalize("{\"answer\":\"ok\",\"confidence\":\"high\"}", Set.of());
        assertThat(out.get("confidence").asText()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("sin answer falla acá, donde el motivo todavía se puede explicar")
    void missingAnswerFailsLoudly() {
        // Si esto pasara, Dev Agent descartaría la etapa y reportaría "Missing dev-answer stage
        // output", que no dice nada sobre la causa real.
        assertThatThrownBy(() -> normalize("{\"confidence\":\"HIGH\"}", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("answer");
    }

    @Test
    @DisplayName("sin sources devuelve un array vacío, nunca ausente")
    void missingSourcesBecomesEmptyArray() throws Exception {
        JsonNode out = normalize("{\"answer\":\"ok\",\"confidence\":\"LOW\"}", Set.of());
        assertThat(out.get("sources").isArray()).isTrue();
        assertThat(out.get("sources")).isEmpty();
    }

    @Test
    @DisplayName("una fuente sin title recibe uno por defecto en vez de perderse")
    void blankTitleGetsDefault() throws Exception {
        JsonNode out =
                normalize("{\"answer\":\"ok\",\"confidence\":\"LOW\",\"sources\":[{\"chunkId\":\"c1\"}]}", Set.of("c1"));
        // El parser de Dev Agent descarta las fuentes con title vacío; sin este default la cita
        // válida se perdería en silencio.
        assertThat(out.get("sources")).hasSize(1);
        assertThat(out.get("sources").get(0).get("title").asText()).isEqualTo("Knowledge");
    }
}
