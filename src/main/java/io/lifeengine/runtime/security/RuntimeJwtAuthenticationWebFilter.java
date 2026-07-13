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
import reactor.core.scheduler.Schedulers;

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
        // KAN-105: token parsing can now involve a JWKS HTTP fetch (JwksPublicKeyProvider), which
        // blocks. This filter runs on a Reactor Netty event-loop thread, where any direct .block()
        // trips Reactor's non-blocking-thread check. Run the whole parse step on boundedElastic
        // instead of just the fetch — a blocking call anywhere in a synchronous call stack that
        // started on the event loop is still on the event loop.
        return Mono.fromCallable(() -> resolveOutcome(auth, exchange))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(outcome -> continueFilter(outcome, auth, exchange, chain));
    }

    private RuntimeJwtService.ParseOutcome resolveOutcome(String auth, ServerWebExchange exchange) {
        var outcome = jwtService.parseAuthorizationHeader(auth);
        if (outcome.principal().isEmpty() && isSseEndpoint(exchange)) {
            // EventSource can't set headers; fall back to ?access_token= for SSE GETs only.
            String token = exchange.getRequest().getQueryParams().getFirst("access_token");
            if (token != null && !token.isBlank()) {
                outcome = jwtService.parseToken(token);
            }
        }
        return outcome;
    }

    private Mono<Void> continueFilter(
            RuntimeJwtService.ParseOutcome outcome,
            String auth,
            ServerWebExchange exchange,
            WebFilterChain chain) {
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
        if (isSseEndpoint(exchange)) {
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

    /**
     * True for any GET that delivers an SSE stream where {@code ?access_token=} fallback is
     * acceptable. Covers:
     *
     * <ul>
     *   <li>{@code /api/runtime/runs/{id}/stream} and the legacy {@code /events} alias —
     *       per-run timeline streams.
     *   <li>{@code /api/runtime/events/stream} — the Global Runtime Event Spine. Browser
     *       EventSource clients (Mission Control, Crypto Watch) can't set Authorization
     *       headers and must use the query-param fallback.
     * </ul>
     */
    static boolean isSseEndpoint(ServerWebExchange exchange) {
        if (!org.springframework.http.HttpMethod.GET.equals(exchange.getRequest().getMethod())) {
            return false;
        }
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/api/runtime/runs/")
                && (path.endsWith("/stream") || path.endsWith("/events"))) {
            return true;
        }
        return path.equals("/api/runtime/events/stream");
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
