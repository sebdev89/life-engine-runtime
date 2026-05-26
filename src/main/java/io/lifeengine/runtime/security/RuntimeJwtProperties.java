package io.lifeengine.runtime.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Same secret contract as life-engine {@code lifeengine.security.jwt.secret}. */
@ConfigurationProperties(prefix = "lifeengine.security.jwt")
public record RuntimeJwtProperties(String secret) {}
