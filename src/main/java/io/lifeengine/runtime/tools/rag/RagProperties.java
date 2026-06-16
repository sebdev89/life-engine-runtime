package io.lifeengine.runtime.tools.rag;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "runtime.tools.rag")
public record RagProperties(boolean enabled, String baseUrl, String defaultCollectionId, int defaultTopK, Duration timeout) {

    public RagProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:8095";
        if (defaultCollectionId == null) defaultCollectionId = "";
        if (defaultTopK <= 0) defaultTopK = 5;
        if (timeout == null) timeout = Duration.ofSeconds(10);
    }
}
