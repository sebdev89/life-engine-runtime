package io.lifeengine.runtime.ext.cryptomarketreview.cryptobot;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the outbound cryptobot-service client used by the seven crypto market-review
 * tools. Defaults work out of the box for local dev where cryptobot-service listens on :8091.
 *
 * <pre>
 * lifeengine.runtime.ext.crypto-market-review.cryptobot.base-url=http://localhost:8091
 * lifeengine.runtime.ext.crypto-market-review.cryptobot.timeout=3s
 * </pre>
 */
@ConfigurationProperties(prefix = "lifeengine.runtime.ext.crypto-market-review.cryptobot")
public record CryptobotProperties(String baseUrl, Duration timeout) {

    public CryptobotProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8091";
        } else {
            baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(3);
        }
    }
}
