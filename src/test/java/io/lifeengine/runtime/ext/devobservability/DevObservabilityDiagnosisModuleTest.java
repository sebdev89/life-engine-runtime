package io.lifeengine.runtime.ext.devobservability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.ext.devobservability.stages.DevObservabilityDiagnosisAgent;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fija el contrato con Dev Agent y la regla que hace confiable al diagnóstico: una causa sólo
 * sobrevive si cita evidencia que realmente trajo datos.
 */
class DevObservabilityDiagnosisModuleTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DevObservabilityDiagnosisAgent agent =
            new DevObservabilityDiagnosisAgent(null, mapper, null);

    @Test
    @DisplayName("El workflowId y el stageId son contrato con Dev Agent")
    void identifiersAreContract() {
        assertThat(DevObservabilityDiagnosisModule.WORKFLOW_ID).isEqualTo("dev.observability-diagnosis.v1");
        // DiagnosisClient del lado de Dev Agent depende de este nombre exacto.
        assertThat(DevObservabilityDiagnosisModule.STAGE_DIAGNOSIS).isEqualTo("observability-diagnosis");
        assertThat(DevObservabilityDiagnosisAgent.AGENT_ID).isEqualTo("dev-observability-diagnosis-agent");
    }

    @Test
    @DisplayName("Una causa con cita válida sobrevive")
    void validCitationSurvives() throws Exception {
        String raw =
                """
                {"probableCause":"El heap se agotó a las 22:40",
                 "confidence":"MEDIUM",
                 "citedEvidenceIds":["e1"],
                 "reasoning":"jvm_memory_used_bytes llegó al límite"}
                """;

        JsonNode out = mapper.readTree(agent.normalize(raw, Set.of("e1", "e2")));

        assertThat(out.path("probableCause").asText()).contains("heap");
        assertThat(out.path("confidence").asText()).isEqualTo("MEDIUM");
        assertThat(out.path("citedEvidenceIds")).hasSize(1);
    }

    @Test
    @DisplayName("Una cita a evidencia inexistente tira abajo la causa")
    void inventedCitationDropsCause() throws Exception {
        String raw =
                """
                {"probableCause":"Seguro que es la base de datos",
                 "confidence":"HIGH",
                 "citedEvidenceIds":["no-existe"],
                 "reasoning":"intuición"}
                """;

        JsonNode out = mapper.readTree(agent.normalize(raw, Set.of("e1")));

        assertThat(out.path("probableCause").isNull()).isTrue();
        assertThat(out.path("confidence").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(out.path("citedEvidenceIds")).isEmpty();
    }

    @Test
    @DisplayName("No se puede sostener una causa citando un pilar que no pudo consultarse")
    void cannotCiteUnavailablePillar() throws Exception {
        // 'e-unavailable' no está en el conjunto citable: el llamador sólo incluye outcome=DATA.
        String raw =
                """
                {"probableCause":"No hubo errores en los logs, así que es la red",
                 "confidence":"HIGH",
                 "citedEvidenceIds":["e-unavailable"],
                 "reasoning":"los logs estaban limpios"}
                """;

        JsonNode out = mapper.readTree(agent.normalize(raw, Set.of("e-metrics")));

        assertThat(out.path("probableCause").isNull()).isTrue();
        assertThat(out.path("confidence").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test
    @DisplayName("Sin causa, INSUFFICIENT_EVIDENCE es una salida válida y esperada")
    void noCauseIsValid() throws Exception {
        String raw =
                """
                {"probableCause":null,
                 "confidence":"INSUFFICIENT_EVIDENCE",
                 "citedEvidenceIds":[],
                 "reasoning":"Loki no respondió y las métricas no muestran anomalías"}
                """;

        JsonNode out = mapper.readTree(agent.normalize(raw, Set.of("e1")));

        assertThat(out.path("probableCause").isNull()).isTrue();
        assertThat(out.path("confidence").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(out.path("reasoning").asText()).contains("Loki");
    }

    @Test
    @DisplayName("Tolera el cerco de markdown que agregan los modelos locales")
    void toleratesMarkdownFences() throws Exception {
        String raw = "```json\n{\"probableCause\":\"causa\",\"confidence\":\"LOW\","
                + "\"citedEvidenceIds\":[\"e1\"],\"reasoning\":\"r\"}\n```";

        JsonNode out = mapper.readTree(agent.normalize(raw, Set.of("e1")));

        assertThat(out.path("probableCause").asText()).isEqualTo("causa");
    }

    @Test
    @DisplayName("Una confianza fuera del enum se degrada a LOW, nunca a HIGH")
    void unknownConfidenceDegradesToLow() throws Exception {
        String raw =
                """
                {"probableCause":"causa","confidence":"MUY ALTA",
                 "citedEvidenceIds":["e1"],"reasoning":"r"}
                """;

        JsonNode out = mapper.readTree(agent.normalize(raw, Set.of("e1")));

        assertThat(out.path("confidence").asText()).isEqualTo("LOW");
    }
}
