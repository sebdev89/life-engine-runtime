package io.lifeengine.runtime.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Validates Bearer JWT (life-engine format) before Spring Security authorization. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RuntimeJwtAuthenticationWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RuntimeJwtAuthenticationWebFilter.class);

    private final RuntimeJwtService jwtService;
    private final RuntimeSecurityProperties securityProperties;
    private final ObjectMapper objectMapper;

    public RuntimeJwtAuthenticationWebFilter(
            RuntimeJwtService jwtService,
            RuntimeSecurityProperties securityProperties,
            ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.securityProperties = securityProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // CORS preflight: browsers send OPTIONS without `Authorization` headers and treat any 4xx
        // as a CORS failure. Let `CorsWebFilter` (ordered ahead of this filter) handle the
        // preflight; if it doesn't short-circuit, Spring Security has `permitAll(OPTIONS, /**)`
        // as a belt-and-braces fallback. We must NOT validate a JWT here.
        if (isPreflight(exchange)) {
            return chain.filter(exchange);
        }
        if (!securityProperties.enabled() || shouldSkip(exchange)) {
            return chain.filter(exchange);
        }
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        var outcome = jwtService.parseAuthorizationHeader(auth);
        if (outcome.principal().isEmpty() && isSseRunEndpoint(exchange)) {
            // EventSource can't set headers; fall back to ?access_token= for SSE GETs only.
            String token = exchange.getRequest().getQueryParams().getFirst("access_token");
            if (token != null && !token.isBlank()) {
                outcome = jwtService.parseToken(token);
            }
        }
        if (outcome.principal().isEmpty()) {
            String reason = outcome.failureReason().orElse("unknown");
            log.warn(
                    "runtime_jwt rejected path={} method={} reason={}",
                    exchange.getRequest().getPath().value(),
                    exchange.getRequest().getMethod(),
                    reason);
            return writeJson(exchange, HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication required");
        }
        RuntimePrincipal principal = outcome.principal().orElseThrow();
        var authorities =
                principal.authorities().stream()
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toList());
        // Store the raw bearer token in the Authentication credentials slot so reactive outbound
        // filters (e.g. CryptobotWebClient) can propagate it to downstream services. This is the
        // explicit Phase-1 JWT pass-through model — the alternative (re-signing or exchanging
        // tokens) is deferred to a later phase.
        String rawToken = extractRawBearer(auth, exchange);
        var authentication =
                new UsernamePasswordAuthenticationToken(principal, rawToken, authorities);
        return chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }

    private static String extractRawBearer(String authHeader, ServerWebExchange exchange) {
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7) && authHeader.length() > 7) {
            return authHeader.substring(7).trim();
        }
        if (isSseRunEndpoint(exchange)) {
            String token = exchange.getRequest().getQueryParams().getFirst("access_token");
            if (token != null && !token.isBlank()) {
                return token.trim();
            }
        }
        return null;
    }

    static boolean shouldSkip(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        return path.startsWith("/actuator/health")
                || path.equals("/api/runtime/health")
                || path.equals("/actuator/prometheus");
    }

    /**
     * True for CORS preflight: {@code OPTIONS} requests carrying the {@code Origin} header.
     * Plain {@code OPTIONS} without {@code Origin} (rare; non-browser tooling) is treated the
     * same way — we still don't want to demand a JWT for a method that returns no body.
     */
    static boolean isPreflight(ServerWebExchange exchange) {
        return HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod());
    }

    static boolean isSseRunEndpoint(ServerWebExchange exchange) {
        if (!org.springframework.http.HttpMethod.GET.equals(exchange.getRequest().getMethod())) {
            return false;
        }
        String path = exchange.getRequest().getPath().value();
        // Matches /api/runtime/runs/{id}/stream and the legacy /events alias.
        return path.startsWith("/api/runtime/runs/")
                && (path.endsWith("/stream") || path.endsWith("/events"));
    }

    private Mono<Void> writeJson(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] body = objectMapper.writeValueAsBytes(new ApiErrorBody(code, message));
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        } catch (Exception ex) {
            return Mono.error(ex);
        }
    }

    private record ApiErrorBody(String code, String message) {}
}
