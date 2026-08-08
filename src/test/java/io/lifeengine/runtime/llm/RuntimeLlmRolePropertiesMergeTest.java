package io.lifeengine.runtime.llm;

import java.time.Duration;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Precedencia de {@link RuntimeLlmRoleProperties#merge(RuntimeLlmProperties)}: rol &gt; default,
 * campo por campo, para TODOS los componentes de {@link RuntimeLlmProperties}.
 *
 * <p>Un campo sin cobertura acá es un campo que se puede dejar de heredar sin que nadie se entere:
 * el YAML sigue diciendo una cosa y el cuerpo de la request lleva otra.
 */
class RuntimeLlmRolePropertiesMergeTest {

    private static final LlmRetryConfig DEFAULT_RETRY = new LlmRetryConfig(true, 2, 200L);
    private static final LlmRetryConfig ROLE_RETRY = new LlmRetryConfig(false, 0, 0L);

    private static RuntimeLlmProperties defaults() {
        return new RuntimeLlmProperties(
                "http://default-host:8000",
                "default-model",
                "default-key",
                Duration.ofSeconds(30),
                256,
                0.1d,
                DEFAULT_RETRY,
                "json_object");
    }

    private static RuntimeLlmRoleProperties role(
            String baseUrl,
            String model,
            String apiKey,
            Duration timeout,
            Integer maxTokens,
            Double temperature,
            LlmRetryConfig retry,
            String responseFormat) {
        return new RuntimeLlmRoleProperties(
                baseUrl, model, apiKey, timeout, maxTokens, temperature, retry, responseFormat);
    }

    @Nested
    class SinOverrides {

        /** Un rol vacío tiene que ser indistinguible del default en los OCHO campos. */
        @Test
        void emptyRole_inheritsEveryFieldFromDefaults() {
            RuntimeLlmProperties merged = RuntimeLlmRoleProperties.empty().merge(defaults());

            Assertions.assertThat(merged.baseUrl()).isEqualTo("http://default-host:8000");
            Assertions.assertThat(merged.model()).isEqualTo("default-model");
            Assertions.assertThat(merged.apiKey()).isEqualTo("default-key");
            Assertions.assertThat(merged.timeout()).isEqualTo(Duration.ofSeconds(30));
            Assertions.assertThat(merged.maxTokens()).isEqualTo(256);
            Assertions.assertThat(merged.temperature()).isEqualTo(0.1d);
            Assertions.assertThat(merged.retry()).isEqualTo(DEFAULT_RETRY);
            Assertions.assertThat(merged.responseFormat()).isEqualTo("json_object");
        }

        /**
         * Regresión del bug silencioso: si {@code merge()} usa el constructor de conveniencia de 7
         * argumentos, {@code responseFormat} viaja en null y el constructor canónico lo normaliza de
         * vuelta a {@code json_object}. El operador desactiva el formato con {@code
         * RUNTIME_LLM_RESPONSE_FORMAT=none} y el rol se lo vuelve a poner sin decir nada.
         */
        @Test
        void roleDoesNotResurrectResponseFormat_whenOperatorDisabledIt() {
            RuntimeLlmProperties disabled =
                    new RuntimeLlmProperties(
                            "http://default-host:8000",
                            "default-model",
                            "default-key",
                            Duration.ofSeconds(30),
                            256,
                            0.1d,
                            DEFAULT_RETRY,
                            "none");

            RuntimeLlmProperties merged = RuntimeLlmRoleProperties.empty().merge(disabled);

            Assertions.assertThat(merged.responseFormat()).isEqualTo("none");
            Assertions.assertThat(merged.responseFormatOrNull())
                    .as("con `none` el campo no debe viajar en el cuerpo")
                    .isNull();
        }

        /** Blancos y vacíos cuentan como "no declarado", no como "override a vacío". */
        @Test
        void blankStrings_countAsNotDeclared() {
            RuntimeLlmProperties merged =
                    role("   ", "", null, null, null, null, null, "  ").merge(defaults());

            Assertions.assertThat(merged.baseUrl()).isEqualTo("http://default-host:8000");
            Assertions.assertThat(merged.model()).isEqualTo("default-model");
            Assertions.assertThat(merged.responseFormat()).isEqualTo("json_object");
        }

        /** {@code maxTokens} no positivo es un valor inválido, no un override. */
        @Test
        void nonPositiveMaxTokens_fallsBackToDefault() {
            Assertions.assertThat(role(null, null, null, null, 0, null, null, null).merge(defaults()).maxTokens())
                    .isEqualTo(256);
            Assertions.assertThat(role(null, null, null, null, -5, null, null, null).merge(defaults()).maxTokens())
                    .isEqualTo(256);
        }
    }

    @Nested
    class ConOverrides {

        /** Con los ocho campos declarados, no debe sobrevivir NADA del default. */
        @Test
        void fullyPopulatedRole_overridesEveryField() {
            RuntimeLlmProperties merged =
                    role(
                                    "http://role-host:11434",
                                    "role-model",
                                    "role-key",
                                    Duration.ofSeconds(90),
                                    4096,
                                    0.7d,
                                    ROLE_RETRY,
                                    "none")
                            .merge(defaults());

            Assertions.assertThat(merged.baseUrl()).isEqualTo("http://role-host:11434");
            Assertions.assertThat(merged.model()).isEqualTo("role-model");
            Assertions.assertThat(merged.apiKey()).isEqualTo("role-key");
            Assertions.assertThat(merged.timeout()).isEqualTo(Duration.ofSeconds(90));
            Assertions.assertThat(merged.maxTokens()).isEqualTo(4096);
            Assertions.assertThat(merged.temperature()).isEqualTo(0.7d);
            Assertions.assertThat(merged.retry()).isEqualTo(ROLE_RETRY);
            Assertions.assertThat(merged.responseFormat()).isEqualTo("none");
        }

        @Test
        void baseUrlAndModel_areTrimmed() {
            RuntimeLlmProperties merged =
                    role("  http://role-host:11434  ", "  role-model  ", null, null, null, null, null, "  none  ")
                            .merge(defaults());

            Assertions.assertThat(merged.baseUrl()).isEqualTo("http://role-host:11434");
            Assertions.assertThat(merged.model()).isEqualTo("role-model");
            Assertions.assertThat(merged.responseFormat()).isEqualTo("none");
        }

        /** Overridear un solo campo no debe arrastrar ninguno de los otros siete. */
        @Test
        void singleFieldOverride_leavesTheOtherSevenUntouched() {
            RuntimeLlmProperties merged =
                    role(null, "solo-el-modelo", null, null, null, null, null, null).merge(defaults());

            Assertions.assertThat(merged.model()).isEqualTo("solo-el-modelo");
            Assertions.assertThat(merged.baseUrl()).isEqualTo("http://default-host:8000");
            Assertions.assertThat(merged.apiKey()).isEqualTo("default-key");
            Assertions.assertThat(merged.timeout()).isEqualTo(Duration.ofSeconds(30));
            Assertions.assertThat(merged.maxTokens()).isEqualTo(256);
            Assertions.assertThat(merged.temperature()).isEqualTo(0.1d);
            Assertions.assertThat(merged.retry()).isEqualTo(DEFAULT_RETRY);
            Assertions.assertThat(merged.responseFormat()).isEqualTo("json_object");
        }

        /** Cero es un override legítimo de temperature, no un "sin declarar". */
        @Test
        void zeroTemperature_isAnOverride_notAnAbsentValue() {
            RuntimeLlmProperties merged =
                    role(null, null, null, null, null, 0.0d, null, null).merge(defaults());

            Assertions.assertThat(merged.temperature()).isEqualTo(0.0d);
        }

        /** Un apiKey vacío es un override real: hay proveedores locales que no aceptan Bearer. */
        @Test
        void emptyApiKey_isAnOverride() {
            RuntimeLlmProperties merged =
                    role(null, null, "", null, null, null, null, null).merge(defaults());

            Assertions.assertThat(merged.apiKey()).isEmpty();
        }
    }

    /**
     * Guardia de aridad: si alguien le agrega un componente a {@link RuntimeLlmProperties} sin
     * agregarlo también a {@link RuntimeLlmRoleProperties}, ese campo deja de ser overrideable en
     * silencio. Este test falla y obliga a decidir explícitamente.
     */
    @Test
    void bothRecordsExposeTheSameNumberOfComponents() {
        Assertions.assertThat(RuntimeLlmRoleProperties.class.getRecordComponents())
                .as("todo campo de RuntimeLlmProperties tiene que ser overrideable por rol")
                .hasSameSizeAs(RuntimeLlmProperties.class.getRecordComponents());
    }
}
