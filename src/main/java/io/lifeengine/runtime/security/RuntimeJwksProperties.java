package io.lifeengine.runtime.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** URI of the Auth service JWKS endpoint for RS256 token verification. Empty = HS256 mode. */
@ConfigurationProperties(prefix = "lifeengine.runtime.security.jwks")
public record RuntimeJwksProperties(String uri) {}
