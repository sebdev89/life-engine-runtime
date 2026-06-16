package io.lifeengine.runtime.tools.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties(RagProperties.class)
@ConditionalOnProperty(name = "runtime.tools.rag.enabled", havingValue = "true")
class RagToolConfig {

    @Bean("ragWebClient")
    WebClient ragWebClient(RagProperties props) {
        return WebClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader("Accept", "application/json")
                .filter(jwtPropagationFilter())
                .build();
    }

    static ExchangeFilterFunction jwtPropagationFilter() {
        return (request, next) ->
                ReactiveSecurityContextHolder.getContext()
                        .map(ctx -> ctx.getAuthentication())
                        .filter(auth -> auth != null && auth.getCredentials() instanceof String)
                        .map(auth -> (String) auth.getCredentials())
                        .filter(token -> !token.isBlank())
                        .map(token ->
                                ClientRequest.from(request)
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                        .build())
                        .defaultIfEmpty(request)
                        .flatMap(next::exchange);
    }
}
