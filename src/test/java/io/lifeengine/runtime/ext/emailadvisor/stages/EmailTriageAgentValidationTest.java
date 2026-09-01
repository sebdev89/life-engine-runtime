package io.lifeengine.runtime.ext.emailadvisor.stages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.runtime.ext.emailadvisor.EmailTriagePrompts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validación estricta de la salida del clasificador (KAN-234).
 *
 * <p>Sin red y sin modelo: se prueba el contrato, no la inferencia. Que el modelo acierte la
 * categoría es otra cosa y se mide en Business Chat con fixtures; lo que se afirma acá es que una
 * salida malformada <b>se rechaza</b> en vez de propagarse hacia la base.
 */
class EmailTriageAgentValidationTest {

    private final EmailTriageAgent agent = new EmailTriageAgent(null);

    private static final String VALID =
            """
            {"category":"RECRUITER","priority":"HIGH","needsReply":true,"informationOnly":false,
             "canBeDrafted":true,"summary":"Piden disponibilidad para entrevista",
             "suggestedAction":"Responder hoy con franjas horarias","confidence":0.94,
             "reason":"Remitente de dominio de recruiting"}
            """;

    @Test
    @DisplayName("una salida válida se canonicaliza y lleva la versión del prompt")
    void validOutputIsCanonicalized() {
        String canonical = agent.validate(VALID);

        assertThat(canonical)
                .contains("\"category\":\"RECRUITER\"")
                .contains("\"priority\":\"HIGH\"")
                .contains("\"needsReply\":true")
                .contains("\"promptVersion\":\"" + EmailTriagePrompts.VERSION + "\"");
    }

    @Test
    @DisplayName("una categoría inventada se rechaza")
    void unknownCategoryIsRejected() {
        assertThatThrownBy(() -> agent.validate(VALID.replace("RECRUITER", "AMIGOS")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("category fuera del vocabulario");
    }

    @Test
    @DisplayName("una prioridad inventada se rechaza")
    void unknownPriorityIsRejected() {
        assertThatThrownBy(() -> agent.validate(VALID.replace("\"HIGH\"", "\"CRITICA\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priority fuera del vocabulario");
    }

    @Test
    @DisplayName("informationOnly=true con needsReply=true se rechaza: es incoherente")
    void informationOnlyImpliesNoReply() {
        String incoherent =
                VALID.replace("\"informationOnly\":false", "\"informationOnly\":true");

        assertThatThrownBy(() -> agent.validate(incoherent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("informationOnly=true exige needsReply=false");
    }

    @Test
    @DisplayName("needsReply=false con informationOnly=false SÍ se acepta: hay acción sin respuesta")
    void actionWithoutReplyIsValid() {
        // El caso de "verificá tu cuenta antes del viernes": no hay que contestar nada, pero hay algo
        // que hacer. Si la implicación se aplicara al revés, este correo quedaría marcado como puro
        // aviso y su acción se perdería.
        String actionable =
                """
                {"category":"BANK_OR_FINANCE","priority":"HIGH","needsReply":false,
                 "informationOnly":false,"canBeDrafted":false,
                 "summary":"El banco pide verificar la cuenta antes del viernes",
                 "suggestedAction":"Verificar la cuenta en la banca en línea","confidence":0.9,
                 "reason":"Aviso del banco con fecha límite y una acción concreta"}
                """;

        String canonical = agent.validate(actionable);

        assertThat(canonical).contains("\"needsReply\":false").contains("\"informationOnly\":false");
        assertThat(canonical).contains("Verificar la cuenta");
    }

    @Test
    @DisplayName("confidence fuera de rango se rechaza")
    void confidenceOutOfRangeIsRejected() {
        assertThatThrownBy(() -> agent.validate(VALID.replace("0.94", "1.7")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence fuera de");
    }

    @Test
    @DisplayName("una respuesta que no es JSON se rechaza")
    void nonJsonIsRejected() {
        assertThatThrownBy(() -> agent.validate("Claro, te ayudo con eso!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("el JSON dentro de un cerco ```json se acepta: es lo que devuelve gemma3:4b")
    void fencedOutputIsAccepted() {
        // No es una hipótesis: el prompt del sistema dice "sin cercos de código" y gemma3:4b —el
        // modelo del rol `fast`, el que ejecuta este agente— lo envuelve igual. Con el parseo
        // directo anterior, TODO correo real terminaba UNCLASSIFIED por un cerco, sin que el
        // modelo hubiera errado la clasificación.
        String fenced = "```json\n" + VALID.strip() + "\n```\n";

        String canonical = agent.validate(fenced);

        assertThat(canonical)
                .contains("\"category\":\"RECRUITER\"")
                .contains("\"promptVersion\":\"" + EmailTriagePrompts.VERSION + "\"");
    }

    @Test
    @DisplayName("tolerar el cerco no afloja la validación de lo que hay adentro")
    void fenceToleranceDoesNotWeakenValidation() {
        String fencedWithBadCategory =
                "```json\n" + VALID.strip().replace("RECRUITER", "AMIGOS") + "\n```";

        assertThatThrownBy(() -> agent.validate(fencedWithBadCategory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("category fuera del vocabulario");
    }

    @Test
    @DisplayName("un campo faltante se rechaza en vez de asumir un default")
    void missingFieldIsRejected() {
        assertThatThrownBy(
                        () -> agent.validate(VALID.replace("\"needsReply\":true,", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needsReply");
    }

    @Test
    @DisplayName("cambiar el prompt obliga a subir su versión")
    void promptChangesRequireAVersionBump() {
        // La versión viaja con cada clasificación persistida. Si el texto cambiara sin subirla, dos
        // clasificaciones producidas por prompts distintos quedarían indistinguibles, y "el
        // clasificador anda peor" pasaría a ser una afirmación que no se puede investigar.
        //
        // Al cambiar el prompt: subí EmailTriagePrompts.VERSION y actualizá este hash.
        assertThat(sha256(EmailTriagePrompts.system()))
                .as(
                        "el prompt cambió — subí EmailTriagePrompts.VERSION y actualizá el hash"
                                + " esperado en este test")
                .isEqualTo(EXPECTED_PROMPT_SHA256);
        assertThat(EmailTriagePrompts.VERSION).isEqualTo("email-triage-v1");
    }

    /** SHA-256 del prompt de la versión declarada. */
    private static final String EXPECTED_PROMPT_SHA256 = "f370f274e71211bf9bf3a44054dddda34776259b7839112c0943e9ae011701e6";

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
