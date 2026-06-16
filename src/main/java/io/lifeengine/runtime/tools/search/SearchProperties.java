package io.lifeengine.runtime.tools.search;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "runtime.tools.search")
public record SearchProperties(boolean enabled, String provider, String tavilyApiKey, Duration timeout) {

    public SearchProperties {
        if (provider == null || provider.isBlank()) {
            provider = "mock";
        }
        if (tavilyApiKey == null) {
            tavilyApiKey = "";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(5);
        }
    }
}
