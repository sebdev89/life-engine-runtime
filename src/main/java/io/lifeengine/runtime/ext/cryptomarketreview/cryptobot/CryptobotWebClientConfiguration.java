package io.lifeengine.runtime.ext.cryptomarketreview.cryptobot;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Builds the {@code cryptobotWebClient} bean used by the seven crypto market-review tools.
 *
 * <p>The {@link ExchangeFilterFunction} reads the bearer token from
 * {@link ReactiveSecurityContextHolder} (placed there by
 * {@code RuntimeJwtAuthenticationWebFilter} as the {@code Authentication.credentials}) and
 * attaches it to every outbound request as {@code Authorization: Bearer ...}. This is the
 * Phase-1 trust model: the runtime acts on behalf of the original caller, so the caller's
 * authority gates the downstream cryptobot-service request too.
 */
@Configuration
@EnableConfigurationProperties(CryptobotProperties.class)
public class CryptobotWebClientConfiguration {

    @Bean("cryptobotWebClient")
    WebClient cryptobotWebClient(CryptobotProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.baseUrl())
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
