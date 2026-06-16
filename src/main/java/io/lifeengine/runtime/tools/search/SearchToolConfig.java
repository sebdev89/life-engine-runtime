package io.lifeengine.runtime.tools.search;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties(SearchProperties.class)
@ConditionalOnProperty(name = "runtime.tools.search.enabled", havingValue = "true")
class SearchToolConfig {

    @Bean
    SearchProvider searchProvider(SearchProperties props) {
        if ("tavily".equalsIgnoreCase(props.provider())) {
            WebClient wc = WebClient.builder()
                    .baseUrl("https://api.tavily.com")
                    .defaultHeader("Content-Type", "application/json")
                    .defaultHeader("Accept", "application/json")
                    .build();
            return new TavilySearchProvider(wc, props.tavilyApiKey(), props.timeout());
        }
        return new MockSearchProvider();
    }
}
