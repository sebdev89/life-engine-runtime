package io.lifeengine.runtime.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties({
    RuntimeSecurityProperties.class,
    RuntimeJwtProperties.class,
    RuntimeJwksProperties.class,
    // KAN-173: contrato de los tokens service-to-service.
    ServiceTokenProperties.class
})
public class RuntimeSecurityConfig {

    @Bean
    SecurityWebFilterChain runtimeSecurityWebFilterChain(
            ServerHttpSecurity http,
            RuntimeSecurityProperties securityProperties,
            RuntimeJwtService jwtService,
            ObjectMapper objectMapper) {
        if (!securityProperties.enabled()) {
            return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .cors(Customizer.withDefaults())
                    .authorizeExchange(ex -> ex.anyExchange().permitAll())
                    .build();
        }
        // KAN-264 — el filtro JWT se instala DENTRO de esta cadena, en la posición AUTHENTICATION.
        //
        // Antes era un `@Component` con `@Order(HIGHEST_PRECEDENCE + 10)`, o sea un WebFilter de la
        // cadena GLOBAL de WebFlux: corría por fuera del WebFilterChainProxy y lo envolvía entero.
        // Con esa disposición, una AccessDeniedException levantada por AuthorizationWebFilter no
        // llegaba a renderizarse y la respuesta salía 200 con cuerpo vacío en lugar de 403 — un
        // rechazo de autorización indistinguible de un éxito sin datos para cualquier cliente.
        //
        // Adentro y en AUTHENTICATION queda por debajo de ExceptionTranslationWebFilter, que es el
        // que traduce la denegación al 403 de `jsonAccessDenied()`.
        //
        // El filtro ya NO lleva `@Component`: si se lo devolviera, correría dos veces —una acá y
        // otra en la cadena global— y el defecto volvería.
        var jwtFilter = new RuntimeJwtAuthenticationWebFilter(jwtService, securityProperties, objectMapper);
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .addFilterAt(jwtFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(jsonEntryPoint())
                                        .accessDeniedHandler(jsonAccessDenied()))
                .authorizeExchange(
                        auth ->
                                auth
                                        // CORS preflight: browsers must be able to OPTIONS any
                                        // path without an Authorization header. The actual
                                        // (non-preflight) request still hits the JWT filter +
                                        // RUNTIME_* authority checks below.
                                        .pathMatchers(HttpMethod.OPTIONS, "/**")
                                        .permitAll()
                                        .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                                        .permitAll()
                                        .pathMatchers(HttpMethod.GET, "/api/runtime/health")
                                        .permitAll()
                                        .pathMatchers(HttpMethod.GET, "/actuator/prometheus")
                                        .permitAll()
                                        // Build identity (KAN-195). Internal-network readable like the other
                                        // scrape endpoints; nginx keeps /actuator/info blocked externally
                                        // (LIFE-OPS-02 §2.2). Must come before the /actuator/** ADMIN rule.
                                        // Carries no secrets.
                                        .pathMatchers(HttpMethod.GET, "/actuator/info")
                                        .permitAll()
                                        .pathMatchers(HttpMethod.GET, "/actuator/metrics/**")
                                        .hasAuthority(RuntimeAuthorities.ADMIN)
                                        .pathMatchers(HttpMethod.GET, "/actuator/**")
                                        .hasAuthority(RuntimeAuthorities.ADMIN)
                                        .pathMatchers(HttpMethod.POST, "/api/runtime/runs")
                                        .hasAuthority(RuntimeAuthorities.OPERATOR)
                                        .pathMatchers(HttpMethod.POST, "/api/runtime/runs/*/cancel")
                                        .hasAuthority(RuntimeAuthorities.OPERATOR)
                                        .pathMatchers("/api/runtime/**")
                                        .hasAuthority(RuntimeAuthorities.VIEWER)
                                        .anyExchange()
                                        .denyAll())
                .build();
    }

    private static ServerAuthenticationEntryPoint jsonEntryPoint() {
        return (exchange, ex) -> {
            exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        };
    }

    private static ServerAccessDeniedHandler jsonAccessDenied() {
        return (exchange, denied) -> {
            exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        };
    }
}
