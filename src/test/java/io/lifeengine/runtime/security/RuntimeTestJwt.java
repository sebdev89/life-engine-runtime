package io.lifeengine.runtime.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;

/** Mints HS256 JWTs compatible with life-engine and {@link RuntimeJwtService}. */
public final class RuntimeTestJwt {

    public static final String TEST_SECRET = "test-jwt-secret-at-least-32-bytes-long!!";

    private RuntimeTestJwt() {}

    /**
     * Token with explicit RUNTIME_* authorities. The {@code role} claim defaults to {@code viewer}
     * (least-privilege) so the Phase-1 {@code derive-runtime-authorities-from-role} bridge does
     * NOT over-amplify what callers pass in — tests assert against the exact authority list.
     */
    public static String bearer(List<String> authorities) {
        return "Bearer " + token(authorities);
    }

    public static String token(List<String> authorities) {
        return token("runtime-test@lifeengine.local", "viewer", authorities);
    }

    /**
     * Mimics a token issued by life-engine-auth today: carries platform {@code role} + platform
     * {@code authorities} (e.g. {@code [ROLE_ADMIN, ROLE_USER]}), with <strong>no</strong>
     * {@code RUNTIME_*} authorities. Used to verify the Phase-1
     * {@code derive-runtime-authorities-from-role} bridge.
     */
    public static String bearerForPlatformRole(String role, List<String> platformAuthorities) {
        return "Bearer " + token("runtime-test@lifeengine.local", role, platformAuthorities);
    }

    /** Same as {@link #bearerForPlatformRole(String, List)} but as the raw JWT (no Bearer prefix). */
    public static String tokenForPlatformRole(String role, List<String> platformAuthorities) {
        return token("runtime-test@lifeengine.local", role, platformAuthorities);
    }

    public static String token(String email, String role, List<String> authorities) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", email)
                .claim("role", role)
                .claim("authorities", authorities)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key)
                .compact();
    }
}
