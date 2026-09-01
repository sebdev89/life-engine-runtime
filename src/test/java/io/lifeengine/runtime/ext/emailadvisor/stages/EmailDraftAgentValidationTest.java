package io.lifeengine.runtime.ext.emailadvisor.stages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.runtime.ext.emailadvisor.EmailDraftPrompts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validación estructural de la salida del redactor (KAN-236).
 *
 * <p>Sin red y sin modelo: se prueba el contrato, no la redacción. Que el borrador esté bien
 * escrito no se puede afirmar en un test, y que no invente datos tampoco — eso lo verifica la
 * persona que aprueba. Lo que sí se afirma acá es que una salida malformada <b>se rechaza</b> en
 * vez de llegar a la bandeja como si fuera un borrador válido.
 */
class EmailDraftAgentValidationTest {

    private final EmailDraftAgent agent = new EmailDraftAgent(null);

    private static final String VALID =
            """
            {"subject":"Re: Falta documentación para el reintegro",
             "body":"Hola Ana,\\n\\nGracias por avisar. Para poder avanzar necesito confirmar qué\\nfactura es la que falta.\\n\\n¿Me confirmás el número?\\n\\nSaludos,\\nSebastián",
             "confidence":0.82,
             "reason":"El correo pide un dato que no está en el mensaje, así que el borrador lo pide"}
            """;

    @Test
    @DisplayName("una salida válida se canonicaliza y lleva la versión del prompt")
    void validOutputIsCanonicalized() {
        String canonical = agent.validate(VALID);

        assertThat(canonical)
                .contains("\"subject\":\"Re: Falta documentación para el reintegro\"")
                .contains("\"promptVersion\":\"" + EmailDraftPrompts.VERSION + "\"");
        assertThat(canonical).contains("Hola Ana");
    }

    @Test
    @DisplayName("el JSON dentro de un cerco ```json se acepta")
    void fencedOutputIsAccepted() {
        // Mismo motivo que en el triage: el modelo lo envuelve aunque el prompt lo prohíba.
        String canonical = agent.validate("```json\n" + VALID.strip() + "\n```\n");

        assertThat(canonical).contains("\"promptVersion\":\"" + EmailDraftPrompts.VERSION + "\"");
    }

    @Test
    @DisplayName("un preámbulo de razonamiento antes del JSON no rompe el parseo")
    void reasoningPreambleIsTolerated() {
        // El rol `chat` en UAT es qwen3:14b, un modelo "thinking": a veces antepone un bloque
        // <think> al JSON. Si eso hiciera fallar el parseo, el borrador se perdería por la forma
        // de la respuesta y no por su contenido.
        String withPreamble =
                "<think>El correo pide dos documentos. Conviene acusar recibo y preguntar.</think>\n"
                        + VALID.strip();

        String canonical = agent.validate(withPreamble);

        assertThat(canonical).contains("\"promptVersion\":\"" + EmailDraftPrompts.VERSION + "\"");
    }

    @Test
    @DisplayName("un cuerpo vacío se rechaza: un borrador en blanco no es un borrador")
    void blankBodyIsRejected() {
        assertThatThrownBy(() -> agent.validate(VALID.replaceAll("\"body\":\"[^\"]*\"", "\"body\":\"   \"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("falta el campo 'body'");
    }

    @Test
    @DisplayName("un asunto faltante se rechaza en vez de asumir uno")
    void missingSubjectIsRejected() {
        assertThatThrownBy(() -> agent.validate("{\"body\":\"hola\",\"confidence\":0.5}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("falta el campo 'subject'");
    }

    @Test
    @DisplayName("un cuerpo desmedido se rechaza: casi siempre es el modelo divagando")
    void oversizedBodyIsRejected() {
        String huge = "x".repeat(EmailDraftAgent.MAX_BODY_CHARS + 1);
        String json =
                "{\"subject\":\"Re: hola\",\"body\":\"" + huge + "\",\"confidence\":0.5}";

        assertThatThrownBy(() -> agent.validate(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'body' excede");
    }

    @Test
    @DisplayName("confidence fuera de rango se rechaza")
    void confidenceOutOfRangeIsRejected() {
        assertThatThrownBy(() -> agent.validate(VALID.replace("0.82", "4")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence fuera de");
    }

    @Test
    @DisplayName("una respuesta que no es JSON se rechaza")
    void nonJsonIsRejected() {
        assertThatThrownBy(() -> agent.validate("Claro, te redacto la respuesta!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cambiar el prompt obliga a subir su versión")
    void promptChangesRequireAVersionBump() {
        // La versión viaja con cada borrador persistido. Si el texto cambiara sin subirla, dos
        // borradores escritos por prompts distintos quedarían indistinguibles.
        //
        // Al cambiar el prompt: subí EmailDraftPrompts.VERSION y actualizá este hash.
        assertThat(sha256(EmailDraftPrompts.system()))
                .as("el prompt cambió — subí EmailDraftPrompts.VERSION y actualizá el hash")
                .isEqualTo(EXPECTED_PROMPT_SHA256);
        assertThat(EmailDraftPrompts.VERSION).isEqualTo("email-draft-v1");
    }

    /** SHA-256 del prompt de la versión declarada. */
    private static final String EXPECTED_PROMPT_SHA256 = "27a99a555048d6a8f4f5e69893be3727fc07ad774d4b54b795164219873dbfe4";

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
