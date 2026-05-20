package io.lifeengine.runtime.app;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/** Allows BO / Vite dev servers to call the runtime API without a reverse proxy. */
@Configuration
public class WebCorsConfiguration {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(
                List.of("http://localhost:*", "http://127.0.0.1:*"));
        cors.addAllowedMethod("*");
        cors.addAllowedHeader("*");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return new CorsWebFilter(source);
    }
}
