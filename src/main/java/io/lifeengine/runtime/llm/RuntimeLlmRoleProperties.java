package io.lifeengine.runtime.llm;

import java.time.Duration;

/**
 * Override parcial para un cliente LLM por rol. Los campos vacíos heredan de {@link
 * RuntimeLlmProperties}.
 *
 * <p>La regla de precedencia es uniforme y SIN excepciones: si el rol declara el campo, gana el
 * rol; si no, gana el default. Todo campo del record {@code RuntimeLlmProperties} es overrideable,
 * incluidos {@code retry} y {@code responseFormat}. Un campo que no se pudiera overridear sería una
 * excepción silenciosa a la regla, y la próxima persona que lea el YAML no tendría cómo saberlo.
 */
public record RuntimeLlmRoleProperties(
        String baseUrl,
        String model,
        String apiKey,
        Duration timeout,
        Integer maxTokens,
        Double temperature,
        LlmRetryConfig retry,
        String responseFormat) {

    static RuntimeLlmRoleProperties empty() {
        return new RuntimeLlmRoleProperties(null, null, null, null, null, null, null, null);
    }

    /**
     * Funde este override sobre {@code defaults} y devuelve las properties efectivas del rol.
     *
     * <p>Se usa a propósito el constructor CANÓNICO de 8 componentes. El overload de conveniencia
     * de 7 argumentos compila igual pero pasa {@code responseFormat = null}, y el constructor
     * canónico lo normaliza de vuelta a {@code json_object}: un rol terminaría resucitando el
     * formato JSON aunque el operador lo hubiera desactivado con {@code
     * RUNTIME_LLM_RESPONSE_FORMAT=none}. Ese bug es silencioso — el YAML dice una cosa y el cuerpo
     * de la request lleva otra — así que la herencia de este campo va explícita.
     */
    RuntimeLlmProperties merge(RuntimeLlmProperties defaults) {
        return new RuntimeLlmProperties(
                hasText(baseUrl) ? baseUrl.trim() : defaults.baseUrl(),
                hasText(model) ? model.trim() : defaults.model(),
                apiKey != null ? apiKey : defaults.apiKey(),
                timeout != null ? timeout : defaults.timeout(),
                maxTokens != null && maxTokens > 0 ? maxTokens : defaults.maxTokens(),
                temperature != null ? temperature : defaults.temperature(),
                retry != null ? retry : defaults.retry(),
                hasText(responseFormat) ? responseFormat.trim() : defaults.responseFormat());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
