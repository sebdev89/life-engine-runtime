package io.lifeengine.runtime.llm;

import io.lifeengine.runtime.observability.RuntimeMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties({RuntimeLlmProperties.class, RuntimeLlmRolesProperties.class})
public class LlmWebClientConfiguration {

    @Bean
    WebClient llmWebClient(RuntimeLlmProperties properties) {
        return buildWebClient(properties);
    }

    @Bean
    @Primary
    @Qualifier("defaultLlmClient")
    LlmClient defaultLlmClient(
            WebClient llmWebClient, RuntimeLlmProperties properties, RuntimeMetrics metrics) {
        return new OpenAiCompatibleLlmClient(llmWebClient, properties, metrics);
    }

    @Bean
    @Qualifier("chatLlmClient")
    LlmClient chatLlmClient(
            WebClient.Builder builder,
            RuntimeLlmProperties defaults,
            RuntimeLlmRolesProperties roles,
            RuntimeMetrics metrics) {
        RuntimeLlmProperties effective = roles.chatOrEmpty().merge(defaults);
        return new OpenAiCompatibleLlmClient(buildWebClient(effective), effective, metrics);
    }

    @Bean
    @Qualifier("fastLlmClient")
    LlmClient fastLlmClient(
            WebClient.Builder builder,
            RuntimeLlmProperties defaults,
            RuntimeLlmRolesProperties roles,
            RuntimeMetrics metrics) {
        RuntimeLlmProperties effective = roles.fastOrEmpty().merge(defaults);
        return new OpenAiCompatibleLlmClient(buildWebClient(effective), effective, metrics);
    }

    private static WebClient buildWebClient(RuntimeLlmProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.baseUrl().replaceAll("/$", ""))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .build();
    }
}
