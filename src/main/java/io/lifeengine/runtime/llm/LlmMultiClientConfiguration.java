package io.lifeengine.runtime.llm;

import io.lifeengine.runtime.observability.RuntimeMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Produces one {@link LlmClient} bean per {@link LlmModelRole}, each wired to its own endpoint
 * and configuration. Agents migrate to their target role qualifier in Fase 3–5.
 *
 * <p>None of these beans are {@code @Primary}. The default {@link LlmClient} bean
 * ({@link OpenAiCompatibleLlmClient}, {@code @Primary}) continues to serve all agents that have
 * not yet been migrated — preserving backward compatibility through Fase 2.
 */
@Configuration
@EnableConfigurationProperties(RuntimeMultiModelProperties.class)
public class LlmMultiClientConfiguration {

    @Bean("fastLlmWebClient")
    WebClient fastLlmWebClient(RuntimeMultiModelProperties props) {
        return buildWebClient(props.fast());
    }

    @Bean("chatLlmWebClient")
    WebClient chatLlmWebClient(RuntimeMultiModelProperties props) {
        return buildWebClient(props.chat());
    }

    @Bean("smartLlmWebClient")
    WebClient smartLlmWebClient(RuntimeMultiModelProperties props) {
        return buildWebClient(props.smart());
    }

    @Bean("codingLlmWebClient")
    WebClient codingLlmWebClient(RuntimeMultiModelProperties props) {
        return buildWebClient(props.coding());
    }

    @Bean
    @Qualifier("fastLlmClient")
    LlmClient fastLlmClient(
            @Qualifier("fastLlmWebClient") WebClient webClient,
            RuntimeMultiModelProperties props,
            RuntimeMetrics metrics) {
        return new OpenAiCompatibleLlmClient(webClient, props.fast(), metrics, LlmModelRole.FAST);
    }

    @Bean
    @Qualifier("chatLlmClient")
    LlmClient chatLlmClient(
            @Qualifier("chatLlmWebClient") WebClient webClient,
            RuntimeMultiModelProperties props,
            RuntimeMetrics metrics) {
        return new OpenAiCompatibleLlmClient(webClient, props.chat(), metrics, LlmModelRole.CHAT);
    }

    @Bean
    @Qualifier("smartLlmClient")
    LlmClient smartLlmClient(
            @Qualifier("smartLlmWebClient") WebClient webClient,
            RuntimeMultiModelProperties props,
            RuntimeMetrics metrics) {
        return new OpenAiCompatibleLlmClient(webClient, props.smart(), metrics, LlmModelRole.SMART);
    }

    @Bean
    @Qualifier("codingLlmClient")
    LlmClient codingLlmClient(
            @Qualifier("codingLlmWebClient") WebClient webClient,
            RuntimeMultiModelProperties props,
            RuntimeMetrics metrics) {
        return new OpenAiCompatibleLlmClient(webClient, props.coding(), metrics, LlmModelRole.CODING);
    }

    private static WebClient buildWebClient(RuntimeLlmProperties props) {
        return WebClient.builder()
                .baseUrl(props.baseUrl().replaceAll("/$", ""))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .build();
    }
}
