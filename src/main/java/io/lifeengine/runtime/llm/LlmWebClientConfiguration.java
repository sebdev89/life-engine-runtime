package io.lifeengine.runtime.llm;

import io.lifeengine.runtime.observability.RuntimeMetrics;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Construye los clientes LLM del runtime. UNA sola estrategia de construcción: todos los {@link
 * LlmClient} nacen de un {@code @Bean} de esta clase.
 *
 * <p>{@link OpenAiCompatibleLlmClient} NO lleva {@code @Component}. Si lo llevara, el component
 * scan crearía un cuarto cliente —con el {@code WebClient} y las properties por default— además de
 * los tres de acá, y Spring tendría dos definiciones compitiendo por el mismo tipo. El caso feliz
 * seguiría andando por el {@code @Primary}, y la falla aparecería recién cuando alguien inyectara
 * por tipo sin qualifier. Un solo lugar que construya clientes evita toda esa clase de ambigüedad.
 *
 * <p>Cada rol tiene su propio {@code WebClient} porque el {@code baseUrl} y la API key viven en el
 * cliente, no en la request: dos roles apuntando a proveedores distintos no pueden compartirlo.
 */
@Configuration
@EnableConfigurationProperties({RuntimeLlmProperties.class, RuntimeLlmRolesProperties.class})
public class LlmWebClientConfiguration {

    @Bean
    WebClient llmWebClient(WebClient.Builder builder, RuntimeLlmProperties properties) {
        return buildWebClient(builder, properties);
    }

    /** Cliente por default: lo reciben los agentes que no piden un rol explícito. */
    @Bean
    @Primary
    LlmClient defaultLlmClient(
            WebClient llmWebClient, RuntimeLlmProperties properties, RuntimeMetrics metrics) {
        return new OpenAiCompatibleLlmClient(llmWebClient, properties, metrics, "default");
    }

    /** Rol conversacional — lo inyectan los agentes de business-chat con {@code @Qualifier}. */
    @Bean
    LlmClient chatLlmClient(
            WebClient.Builder builder,
            RuntimeLlmProperties defaults,
            RuntimeLlmRolesProperties roles,
            RuntimeMetrics metrics) {
        RuntimeLlmProperties effective = roles.chatOrEmpty().merge(defaults);
        return new OpenAiCompatibleLlmClient(
                buildWebClient(builder, effective), effective, metrics, "chat");
    }

    /** Rol de clasificación/extracción — latencia sobre prosa. */
    @Bean
    LlmClient fastLlmClient(
            WebClient.Builder builder,
            RuntimeLlmProperties defaults,
            RuntimeLlmRolesProperties roles,
            RuntimeMetrics metrics) {
        RuntimeLlmProperties effective = roles.fastOrEmpty().merge(defaults);
        return new OpenAiCompatibleLlmClient(
                buildWebClient(builder, effective), effective, metrics, "fast");
    }

    /**
     * Se parte del {@code WebClient.Builder} de Spring Boot y no de {@code WebClient.builder()}.
     *
     * <p>No es equivalente: el del contexto viene con el {@code ObservationWebClientCustomizer}
     * aplicado, y es lo único que hace que una llamada al LLM produzca un span. Con el builder
     * pelado, la llamada más cara y más frágil de toda la corrida —la que sale del cluster, cruza
     * el NAT y va al modelo— no aparecía en ninguna traza.
     *
     * <p>{@code clone()} porque el builder es mutable y hay tres roles: sin clonar, el
     * {@code baseUrl} del último ganaría y los tres clientes terminarían apuntando al mismo lado.
     */
    private static WebClient buildWebClient(WebClient.Builder builder, RuntimeLlmProperties properties) {
        return builder.clone()
                .baseUrl(properties.baseUrl().replaceAll("/$", ""))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .build();
    }
}
