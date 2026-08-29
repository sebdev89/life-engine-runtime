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
        // Adentro y en AUTHENTICATION queda por debajo de ExceptionTranslationWebFilter, que es el
        // que traduce la denegación al 403 de `jsonAccessDenied()`. Esa es la posición que Spring
        // Security espera de un filtro de autenticación.
        //
        // ESTA DISPOSICIÓN NO ERA LA CAUSA del 200-con-cuerpo-vacío. Se investigó como hipótesis y
        // se REFUTÓ con un A/B de dos contenedores: con el filtro adentro y con el filtro afuera,
        // los dos devolvían 200 vacío. La causa real está más abajo, en los handlers que fijaban el
        // estado y cerraban la respuesta sin escribir cuerpo.
        //
        // El refactor se conserva por su propio mérito —posición correcta— y porque evita la doble
        // registración: si se le devolviera `@Component`, el filtro correría dos veces, una acá y
        // otra en la cadena global.
        var jwtFilter = new RuntimeJwtAuthenticationWebFilter(jwtService, securityProperties, objectMapper);
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .addFilterAt(jwtFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(jsonEntryPoint(objectMapper))
                                        .accessDeniedHandler(jsonAccessDenied(objectMapper)))
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

    /**
     * KAN-264 — estos handlers escriben un CUERPO, no solo un estado.
     *
     * <p>Antes hacian {@code setStatusCode(...)} seguido de {@code setComplete()}, sin cuerpo. Con
     * esa forma, una denegacion de autorizacion salia como <b>200 con cuerpo vacio</b>: para cuando
     * el handler corria, la respuesta ya estaba en estado de commit y el estado nuevo no llegaba al
     * cable. Medido en UAT: {@code applied=true committed=true status=403 FORBIDDEN} sobre el
     * objeto, y 200 en el cliente.
     *
     * <p>El camino que si funcionaba era el 401 que el propio filtro JWT escribe con
     * {@code writeWith(...)} y un cuerpo JSON. Estos handlers ahora hacen lo mismo, con el mismo
     * contrato {@code {code, message}} que devuelve el resto de la API.
     *
     * <p>No es cosmetico: un cliente no puede distinguir un 200 vacio de un exito sin datos, asi
     * que una denegacion silenciosa se lee como permitida. Es la misma familia que KAN-250.
     */
    private static ServerAuthenticationEntryPoint jsonEntryPoint(ObjectMapper objectMapper) {
        return (exchange, ex) ->
                writeJsonError(
                        exchange,
                        objectMapper,
                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "unauthorized",
                        "Authentication required");
    }

    private static Mono<Void> writeJsonError(
            org.springframework.web.server.ServerWebExchange exchange,
            ObjectMapper objectMapper,
            org.springframework.http.HttpStatus status,
            String code,
            String message) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        try {
            byte[] body =
                    objectMapper.writeValueAsBytes(java.util.Map.of("code", code, "message", message));
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        } catch (Exception ex) {
            // Serializar dos claves fijas no puede fallar, pero si fallara es preferible un estado
            // sin cuerpo antes que propagar un error que terminaria en 500 y ocultaria la denegacion.
            return response.setComplete();
        }
    }

    private static ServerAccessDeniedHandler jsonAccessDenied(ObjectMapper objectMapper) {
        return (exchange, denied) ->
                writeJsonError(
                        exchange,
                        objectMapper,
                        org.springframework.http.HttpStatus.FORBIDDEN,
                        "forbidden",
                        "Insufficient authority");
    }
}
