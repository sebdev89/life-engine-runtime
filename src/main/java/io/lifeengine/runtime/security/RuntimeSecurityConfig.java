package io.lifeengine.runtime.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties({RuntimeSecurityProperties.class, RuntimeJwtProperties.class})
public class RuntimeSecurityConfig {

    @Bean
    SecurityWebFilterChain runtimeSecurityWebFilterChain(
            ServerHttpSecurity http, RuntimeSecurityProperties securityProperties) {
        if (!securityProperties.enabled()) {
            return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .cors(Customizer.withDefaults())
                    .authorizeExchange(ex -> ex.anyExchange().permitAll())
                    .build();
        }
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
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
