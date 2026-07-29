package io.lifeengine.runtime.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.yaml.snakeyaml.Yaml;

/**
 * El prefijo de {@link ServiceTokenProperties} tiene que existir de verdad en application.yml.
 *
 * <p><b>Por qué existe (KAN-173).</b> La clase declaraba
 * {@code lifeengine.security.service-tokens} mientras el YAML ponía el bloque bajo
 * {@code lifeengine.runtime.security}. Spring no se queja de un prefijo que no matchea nada:
 * simplemente deja el record con sus defaults. {@code expectedIssuer} quedaba vacío y
 * {@code ServiceTokenGuard} rechazaba TODOS los tokens de servicio con
 * {@code s2s_issuer_not_configured}.
 *
 * <p>Compilaba, arrancaba, los tests unitarios del guard pasaban —construyen las properties a
 * mano— y solo se vio al desplegarlo en UAT. Este test compara las dos fuentes.
 */
class ServiceTokenPropertiesBindingTest {

    @Test
    @DisplayName("el prefijo declarado existe en application.yml")
    @SuppressWarnings("unchecked")
    void prefixResolvesInApplicationYml() {
        String prefix = ServiceTokenProperties.class
                .getAnnotation(ConfigurationProperties.class)
                .prefix();
        assertThat(prefix).isNotBlank();

        Map<String, Object> node;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            node = new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("no se pudo leer application.yml", e);
        }

        for (String segment : prefix.split("\\.")) {
            assertThat(node)
                    .as("el segmento '%s' del prefijo '%s' no existe en application.yml", segment, prefix)
                    .containsKey(segment);
            Object next = node.get(segment);
            assertThat(next).as("'%s' no es un bloque", segment).isInstanceOf(Map.class);
            node = (Map<String, Object>) next;
        }

        // Y que el bloque traiga las claves que el record espera: un prefijo correcto apuntando a un
        // bloque vacío tendría el mismo efecto.
        assertThat(node).containsKeys("expected-issuer", "expected-audience", "required-authority");
    }
}
