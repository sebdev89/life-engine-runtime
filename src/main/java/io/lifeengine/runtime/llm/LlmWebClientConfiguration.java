package io.lifeengine.runtime.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties(RuntimeLlmProperties.class)
public class LlmWebClientConfiguration {

    @Bean
    WebClient llmWebClient(RuntimeLlmProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.baseUrl().replaceAll("/$", ""))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .build();
    }
}
