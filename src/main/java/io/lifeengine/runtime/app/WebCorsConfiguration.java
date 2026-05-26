package io.lifeengine.runtime.app;

import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Allows the auth-ui (:4201), runtime-ui (:4202), and cryptobot-ui (:4203) dev servers to call
 * the runtime API directly without a reverse proxy.
 *
 * <p>The bean is registered at {@link Ordered#HIGHEST_PRECEDENCE} so it runs <em>before</em>
 * {@link io.lifeengine.runtime.security.RuntimeJwtAuthenticationWebFilter} (which sits at
 * {@code HIGHEST_PRECEDENCE + 10}). This ensures browser CORS preflight (OPTIONS) is
 * short-circuited by {@link CorsWebFilter} and never reaches the JWT filter — which would
 * otherwise 401 the preflight because browsers strip {@code Authorization} from OPTIONS.
 *
 * <p>Implementation notes:
 *
 * <ul>
 *   <li>We use {@code allowedOriginPatterns} (NOT {@code allowedOrigins}) — Spring 6.2's
 *       {@code DefaultCorsProcessor} rejects requests with "origin is malformed" when both
 *       collections are populated together.
 *   <li>{@code localhost:*} / {@code 127.0.0.1:*} patterns cover the three documented
 *       front-ends plus any other dev port (Vite ephemeral, Postman web, etc.).
 *   <li>Methods and headers are explicit per the Phase-1 runbook; wildcard {@code *} would
 *       also work but the explicit list makes the contract self-documenting.
 * </ul>
 */
@Configuration
public class WebCorsConfiguration {

    /**
     * Local dev origins explicitly documented in the runbook:
     *
     * <ul>
     *   <li>{@code http://localhost:4201} — life-engine-auth-ui
     *   <li>{@code http://localhost:4202} — life-engine-runtime-ui
     *   <li>{@code http://localhost:4203} — cryptobot-ui
     * </ul>
     *
     * Plus a broader {@code http://localhost:*} / {@code http://127.0.0.1:*} for any other
     * local dev tool. All three documented origins are covered by the wildcard patterns.
     */
    private static final List<String> LOCAL_DEV_ORIGIN_PATTERNS =
            List.of("http://localhost:*", "http://127.0.0.1:*");

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(LOCAL_DEV_ORIGIN_PATTERNS);
        cors.setAllowedMethods(
                List.of(
                        HttpMethod.GET.name(),
                        HttpMethod.POST.name(),
                        HttpMethod.PUT.name(),
                        HttpMethod.DELETE.name(),
                        HttpMethod.PATCH.name(),
                        HttpMethod.OPTIONS.name(),
                        HttpMethod.HEAD.name()));
        cors.setAllowedHeaders(
                List.of(
                        HttpHeaders.AUTHORIZATION,
                        HttpHeaders.CONTENT_TYPE,
                        HttpHeaders.ACCEPT,
                        HttpHeaders.CACHE_CONTROL,
                        "X-Requested-With",
                        "X-Correlation-Id",
                        "X-Request-Id",
                        "Last-Event-ID"));
        cors.setExposedHeaders(
                List.of(HttpHeaders.CONTENT_TYPE, "X-Correlation-Id", "X-Request-Id"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(Duration.ofMinutes(10));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        source.registerCorsConfiguration("/actuator/**", cors);
        return new CorsWebFilter(source);
    }
}
