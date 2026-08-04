package io.lifeengine.runtime.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "runtime.llm")
public record RuntimeLlmProperties(
        String baseUrl,
        String model,
        String apiKey,
        Duration timeout,
        int maxTokens,
        double temperature,
        LlmRetryConfig retry,
        String responseFormat) {

    @ConstructorBinding
    public RuntimeLlmProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8000";
        }
        if (model == null || model.isBlank()) {
            model = "Qwen/Qwen2.5-Coder-3B-Instruct";
        }
        if (apiKey == null) {
            apiKey = "local-dev";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(30);
        }
        if (maxTokens <= 0) {
            maxTokens = 256;
        }
        if (retry == null) {
            // Conservative default: retry transient transport/provider failures up to twice with
            // a small fixed backoff. Disable by setting `runtime.llm.retry.enabled: false`.
            retry = new LlmRetryConfig(true, 2, 200L);
        }
        if (responseFormat == null) {
            // Los DIEZ agentes que llaman al LLM en este runtime parsean la respuesta con
            // StrictAgentJson. Ninguno consume prosa. Pedirle JSON al proveedor es entonces lo
            // que todos los consumidores ya exigen, y no un caso especial de uno.
            //
            // Antes se pedía sólo en el prompt. Eso funciona mientras el prompt es corto y falla
            // en cuanto crece: al ligarle el corpus al bot de ventas, qwen3:14b empezó a devolver
            // 2040 caracteres de markdown perfectamente correcto —citaba el documento y su score—
            // que el parser no podía usar porque no había ningún JSON adentro.
            //
            // Vaciar la variable vuelve al comportamiento anterior, por si un proveedor no acepta
            // el campo.
            responseFormat = "json_object";
        }
    }

    /**
     * El objeto {@code response_format} tal como lo espera la API compatible con OpenAI, o
     * {@code null} si está desactivado — en cuyo caso el campo no viaja en el cuerpo.
     */
    public ResponseFormat responseFormatOrNull() {
        return responseFormat == null || responseFormat.isBlank() || "none".equalsIgnoreCase(responseFormat)
                ? null
                : new ResponseFormat(responseFormat);
    }

    /** {@code {"type": "json_object"}} — la forma que definen OpenAI, vLLM y Ollama. */
    public record ResponseFormat(String type) {}

    /**
     * Convenience constructor for tests that predate the retry field.
     *
     * <p>Uses the conservative default retry policy so existing call sites keep compiling without
     * having to thread retry config through.
     */
    public RuntimeLlmProperties(
            String baseUrl,
            String model,
            String apiKey,
            Duration timeout,
            int maxTokens,
            double temperature) {
        this(baseUrl, model, apiKey, timeout, maxTokens, temperature, null, null);
    }

    /** Constructor de conveniencia para los tests anteriores al campo {@code responseFormat}. */
    public RuntimeLlmProperties(
            String baseUrl,
            String model,
            String apiKey,
            Duration timeout,
            int maxTokens,
            double temperature,
            LlmRetryConfig retry) {
        this(baseUrl, model, apiKey, timeout, maxTokens, temperature, retry, null);
    }
}
