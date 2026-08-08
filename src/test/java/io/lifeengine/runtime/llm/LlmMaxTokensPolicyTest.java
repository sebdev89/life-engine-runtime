package io.lifeengine.runtime.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Política única de {@code max-tokens}: un solo número, y ningún ambiente por debajo del piso con
 * el que {@code business-chat.reply.v1} está demostrado que rompe.
 *
 * <p>El fallo real es de comportamiento del modelo —qwen3:14b se va en un bloque de razonamiento,
 * choca contra el techo y devuelve contenido vacío— y no se puede reproducir sin GPU ni proveedor
 * real. Lo que sí se puede fijar en CI, y es lo que hace este test, es la POLÍTICA: que el valor
 * que enviamos no vuelva a caer en la zona que ya se midió rota. Sin esto, bajar el default a 192
 * o a 512 vuelve a pasar la suite entera en verde y se descubre recién con un cliente escribiéndole
 * al bot.
 *
 * <p>Medido contra Ollama, tres mensajes distintos por corrida:
 *
 * <pre>
 *    192   business-chat  0/3 OK   completion_tokens == 192, clavado en el techo
 *    512   business-chat  1/3 OK   completion_tokens == 512, clavado en el techo
 *   1024   business-chat  6/6 OK   máximo real observado: 151
 *   2048   business-chat  9/9 OK   máximo real observado: 104
 *   4096   business-chat  3/3 OK   máximo real observado: 101
 * </pre>
 */
class LlmMaxTokensPolicyTest {

    /** {@code max-tokens: 2048} o {@code max-tokens: ${RUNTIME_LLM_MAX_TOKENS:2048}}. */
    private static final Pattern MAX_TOKENS_LINE =
            Pattern.compile("^\\s*max-tokens:\\s*(?:\\$\\{[A-Z_]+:)?(\\d+)\\}?\\s*$", Pattern.MULTILINE);

    @Test
    void shippedDefaultInApplicationYml_staysAboveTheFloorThatBreaksBusinessChat() throws IOException {
        int shipped = maxTokensDeclaredIn("/application.yml");

        Assertions.assertThat(shipped)
                .as(
                        "192 rompe business-chat.reply.v1 el 100%% de las veces y 512 falla 2 de 3 "
                                + "contra qwen3:14b; el default que enviamos no puede caer ahí")
                .isGreaterThanOrEqualTo(RuntimeLlmProperties.MIN_SAFE_MAX_TOKENS);

        Assertions.assertThat(shipped)
                .as("el YAML y la constante del record son el MISMO número, o hay dos políticas")
                .isEqualTo(RuntimeLlmProperties.DEFAULT_MAX_TOKENS);
    }

    /**
     * La capa más profunda: la pisa quien configure 0, un negativo, o construya las properties a
     * mano. Un fallback tiene que caer en algo que ande.
     */
    @Test
    void recordFallback_forNonPositiveValues_landsOnTheSameSafeDefault() {
        Assertions.assertThat(propertiesWithMaxTokens(0).maxTokens())
                .isEqualTo(RuntimeLlmProperties.DEFAULT_MAX_TOKENS);
        Assertions.assertThat(propertiesWithMaxTokens(-1).maxTokens())
                .isEqualTo(RuntimeLlmProperties.DEFAULT_MAX_TOKENS);

        Assertions.assertThat(RuntimeLlmProperties.DEFAULT_MAX_TOKENS)
                .isGreaterThanOrEqualTo(RuntimeLlmProperties.MIN_SAFE_MAX_TOKENS);
    }

    /**
     * Los roles no declaran {@code max-tokens}, así que el modelo del rol chat —el que rompía— corre
     * con el default global. Si algún día un rol lo overridea, tiene que respetar el mismo piso.
     */
    @Test
    void rolesInheritTheSafeDefault_whenTheyDoNotOverrideIt() {
        RuntimeLlmProperties defaults = propertiesWithMaxTokens(RuntimeLlmProperties.DEFAULT_MAX_TOKENS);

        int chatEffective = RuntimeLlmRoleProperties.empty().merge(defaults).maxTokens();

        Assertions.assertThat(chatEffective)
                .isEqualTo(RuntimeLlmProperties.DEFAULT_MAX_TOKENS)
                .isGreaterThanOrEqualTo(RuntimeLlmProperties.MIN_SAFE_MAX_TOKENS);
    }

    /** El perfil de test no debe volver a pinear su propio número: se desincroniza del real. */
    @Test
    void testProfile_doesNotPinItsOwnMaxTokens() throws IOException {
        String testYml = read("/application-test.yml");

        Assertions.assertThat(MAX_TOKENS_LINE.matcher(testYml).find())
                .as(
                        "application-test.yml no declara max-tokens: los tests corren sobre el "
                                + "default que enviamos")
                .isFalse();
    }

    // ------------------------------------------------------------------ helpers ----

    private static RuntimeLlmProperties propertiesWithMaxTokens(int maxTokens) {
        return new RuntimeLlmProperties(
                "http://localhost:8000",
                "test-model",
                "test-key",
                Duration.ofSeconds(30),
                maxTokens,
                0d,
                null,
                null);
    }

    private static int maxTokensDeclaredIn(String resource) throws IOException {
        Matcher matcher = MAX_TOKENS_LINE.matcher(read(resource));
        Assertions.assertThat(matcher.find())
                .as("no se encontró una línea `max-tokens:` en %s", resource)
                .isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = LlmMaxTokensPolicyTest.class.getResourceAsStream(resource)) {
            Assertions.assertThat(in).as("no se pudo leer %s del classpath", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
