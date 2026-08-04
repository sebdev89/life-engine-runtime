package io.lifeengine.runtime.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El cuerpo que sale hacia el proveedor tiene que pedir JSON, no confiar en que el prompt alcance.
 *
 * <p>Existe por un fallo concreto en UAT. Los diez agentes que llaman al LLM en este runtime
 * parsean la respuesta con {@code StrictAgentJson}; ninguno consume prosa. Aun así, el único lugar
 * donde se pedía JSON era el system prompt.
 *
 * <p>Eso funciona mientras el prompt es corto. Al ligarle el corpus de ventas al bot,
 * {@code qwen3:14b} empezó a devolver 2040 caracteres de markdown perfectamente correcto —citaba
 * el documento recuperado y su score— sin una sola llave. {@code StrictAgentJson} tolera fences,
 * preámbulos y prosa alrededor del objeto, pero no puede extraer un objeto que no está. El
 * workflow moría con {@code invalid JSON: Unrecognized token 'El'} y el visitante leía "tuve un
 * problema técnico".
 *
 * <p>Estos tests miran el JSON serializado, no el record: lo que importa es lo que viaja por el
 * cable. Un campo que existe en Java y no se serializa no sirve de nada.
 */
class ResponseFormatRequestTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static OpenAiCompatibleLlmClient clientWith(String responseFormat) {
        RuntimeLlmProperties props =
                new RuntimeLlmProperties(
                        "http://localhost:11434",
                        "qwen3:14b",
                        "local-dev",
                        Duration.ofSeconds(30),
                        512,
                        0.0,
                        null,
                        responseFormat);
        return new OpenAiCompatibleLlmClient(
                org.springframework.web.reactive.function.client.WebClient.builder().build(),
                props,
                new io.lifeengine.runtime.observability.RuntimeMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    private static String bodyJson(String responseFormat) throws Exception {
        var body =
                clientWith(responseFormat)
                        .buildRequestBody("qwen3:14b", List.of(new LlmMessage("user", "hola")));
        return JSON.writeValueAsString(body);
    }

    @Test
    @DisplayName("por defecto el cuerpo pide response_format json_object")
    void defaultsToJsonObject() throws Exception {
        // null = sin configurar. El default tiene que ser el que necesitan todos los agentes,
        // no el que no rompe nada: "no romper nada" era exactamente el estado roto.
        assertThat(bodyJson(null)).contains("\"response_format\":{\"type\":\"json_object\"}");
    }

    @Test
    @DisplayName("configurado explícitamente, viaja tal cual")
    void honoursExplicitValue() throws Exception {
        assertThat(bodyJson("json_object")).contains("\"response_format\":{\"type\":\"json_object\"}");
    }

    @Test
    @DisplayName("desactivado, el campo NO viaja — no viaja en null")
    void omitsFieldWhenDisabled() throws Exception {
        // Mandar `"response_format": null` no es lo mismo que no mandarlo: hay proveedores que
        // rechazan el campo presente-pero-nulo. La vuelta atrás tiene que ser una ausencia real.
        for (String disabled : new String[] {"", "  ", "none", "NONE"}) {
            assertThat(bodyJson(disabled))
                    .as("con response-format='%s'", disabled)
                    .doesNotContain("response_format");
        }
    }

    @Test
    @DisplayName("el resto del cuerpo no cambia")
    void restOfBodyIsUntouched() throws Exception {
        // El cambio agrega un campo; si además movió max_tokens o temperature, rompe a cualquier
        // proveedor que los esté leyendo.
        String body = bodyJson(null);
        assertThat(body).contains("\"model\":\"qwen3:14b\"");
        assertThat(body).contains("\"max_tokens\":512");
        assertThat(body).contains("\"temperature\":0.0");
        assertThat(body).contains("\"messages\":[{\"role\":\"user\",\"content\":\"hola\"}]");
    }

    @Test
    @DisplayName("los constructores de conveniencia siguen dando el default")
    void convenienceConstructorsKeepTheDefault() {
        // Hay tests y call sites que construyen las propiedades con 6 y con 7 argumentos. Si esas
        // sobrecargas dejaran responseFormat en null-sin-default, el arreglo valdría sólo cuando
        // se levanta por configuración de Spring.
        var seis =
                new RuntimeLlmProperties(
                        "http://x", "m", "k", Duration.ofSeconds(1), 10, 0.0);
        var siete =
                new RuntimeLlmProperties(
                        "http://x", "m", "k", Duration.ofSeconds(1), 10, 0.0, null);

        assertThat(seis.responseFormatOrNull()).isNotNull();
        assertThat(seis.responseFormatOrNull().type()).isEqualTo("json_object");
        assertThat(siete.responseFormatOrNull().type()).isEqualTo("json_object");
    }
}
