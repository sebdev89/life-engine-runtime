package io.lifeengine.runtime.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/** Parses life-engine JWTs (HS256 or RS256); does not issue tokens (platform auth module does). */
@Service
public class RuntimeJwtService {

    private static final String PLATFORM_ROLE_ADMIN = "ROLE_ADMIN";

    private final SecretKey key;
    private final RuntimeSecurityProperties securityProperties;
    private final JwksPublicKeyProvider jwksKeyProvider;

    /** Used only when JWKS verification is configured and no real HS256 secret is set — this
     * service never signs tokens, so the dummy key only matters if an HS256 token somehow
     * arrives while running in JWKS mode (rejected on signature mismatch, same as any other
     * bad token). Mirrors life-engine-auth's JwtService#buildHmacKeyOrDummy. */
    private static final String JWKS_MODE_DUMMY_SECRET = "runtime-jwks-mode-hmac-fallback-dummy-key!!";

    public RuntimeJwtService(
            RuntimeJwtProperties props,
            RuntimeSecurityProperties securityProperties,
            JwksPublicKeyProvider jwksKeyProvider) {
        String secret = props.secret() == null ? "" : props.secret();
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            if (jwksKeyProvider.isConfigured()) {
                // KAN-32 follow-up: JWT_SECRET is no longer required once AUTH_JWKS_URI is set —
                // this service only ever verifies, never signs, so there is nothing to protect.
                bytes = JWKS_MODE_DUMMY_SECRET.getBytes(StandardCharsets.UTF_8);
            } else {
                throw new IllegalStateException(
                        "lifeengine.security.jwt.secret must be at least 32 UTF-8 bytes for HS256");
            }
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.securityProperties = securityProperties;
        this.jwksKeyProvider = jwksKeyProvider;
    }

    public record ParseOutcome(Optional<RuntimePrincipal> principal, Optional<String> failureReason) {
        public static ParseOutcome ok(RuntimePrincipal p) {
            return new ParseOutcome(Optional.of(p), Optional.empty());
        }

        public static ParseOutcome failed(String reason) {
            return new ParseOutcome(Optional.empty(), Optional.of(reason));
        }
    }

    public ParseOutcome parseAuthorizationHeader(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return ParseOutcome.failed("missing");
        }
        if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7) || authorization.length() <= 7) {
            return ParseOutcome.failed("malformed_header");
        }
        return parseToken(authorization.substring(7).trim());
    }

    public ParseOutcome parseToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return ParseOutcome.failed("missing");
        }
        try {
            Claims claims;
            String alg = peekAlg(rawToken);
            if ("RS256".equals(alg) && jwksKeyProvider.isConfigured()) {
                String kid = peekKid(rawToken);
                var pubKey = jwksKeyProvider.getPublicKey(kid);
                if (pubKey.isEmpty()) {
                    return ParseOutcome.failed("jwks_key_not_found");
                }
                claims = Jwts.parser().verifyWith(pubKey.get()).build().parseSignedClaims(rawToken).getPayload();
            } else {
                claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(rawToken).getPayload();
            }
            UUID userId = UUID.fromString(claims.getSubject());
            String email = claims.get("email", String.class);
            String role = claims.get("role", String.class);
            List<String> authorities = readAuthorities(claims);
            if (authorities.isEmpty() && role != null) {
                authorities.add("ROLE_" + role.trim().toUpperCase(Locale.ROOT));
            }
            List<String> effective = withDerivedRuntimeAuthorities(role, authorities);
            return ParseOutcome.ok(new RuntimePrincipal(userId, email, role, List.copyOf(effective)));
        } catch (ExpiredJwtException ex) {
            return ParseOutcome.failed("expired");
        } catch (SignatureException | MalformedJwtException ex) {
            return ParseOutcome.failed("invalid_signature");
        } catch (JwtException | IllegalArgumentException ex) {
            return ParseOutcome.failed("invalid");
        }
    }

    static String peekAlg(String rawToken) {
        return peekHeaderField(rawToken, "\"alg\"", "HS256");
    }

    static String peekKid(String rawToken) {
        return peekHeaderField(rawToken, "\"kid\"", "");
    }

    private static String peekHeaderField(String rawToken, String fieldKey, String defaultValue) {
        try {
            String[] parts = rawToken.split("\\.", 3);
            if (parts.length < 1) return defaultValue;
            int mod = parts[0].length() % 4;
            String padded = mod == 0 ? parts[0] : parts[0] + "====".substring(mod);
            byte[] decoded = Base64.getUrlDecoder().decode(padded);
            String header = new String(decoded, StandardCharsets.UTF_8);
            int fieldIdx = header.indexOf(fieldKey);
            if (fieldIdx < 0) return defaultValue;
            int colon = header.indexOf(':', fieldIdx);
            int start = header.indexOf('"', colon + 1) + 1;
            int end = header.indexOf('"', start);
            if (start <= 0 || end <= start) return defaultValue;
            return header.substring(start, end);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static List<String> readAuthorities(Claims claims) {
        List<String> authorities = new ArrayList<>();
        Object raw = claims.get("authorities");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    authorities.add(item.toString());
                }
            }
        }
        return authorities;
    }

    /**
     * Phase-1 bridge: mint {@code RUNTIME_*} authorities from the platform {@code role} claim and
     * {@code ROLE_ADMIN} so that life-engine-auth tokens (which carry only platform
     * {@code ROLE_*}/{@code AUTH:*} permissions today) can reach the runtime API. Toggle off
     * (i.e. set {@code lifeengine.runtime.security.derive-runtime-authorities-from-role=false})
     * once a future life-engine-auth Flyway migration seeds proper {@code RUNTIME_*} permissions
     * and assigns them via {@code auth_role_permission}.
     *
     * <p>Mapping (only applied when the flag is on AND the source authority is not already
     * present):
     * <ul>
     *   <li>{@code role=ADMIN} or {@code authorities} contains {@code ROLE_ADMIN}
     *       → adds {@link RuntimeAuthorities#VIEWER}, {@link RuntimeAuthorities#OPERATOR},
     *       {@link RuntimeAuthorities#ADMIN}.</li>
     *   <li>{@code role ∈ {OPERATOR, BO_ADMIN}}
     *       → adds {@link RuntimeAuthorities#VIEWER}, {@link RuntimeAuthorities#OPERATOR}.</li>
     *   <li>{@code role ∈ {USER, VIEWER}} → adds {@link RuntimeAuthorities#VIEWER}.</li>
     *   <li>{@code role=GUEST} or unknown → no RUNTIME_* added (authenticated but 403 on runtime).</li>
     * </ul>
     */
    private List<String> withDerivedRuntimeAuthorities(String role, List<String> existing) {
        if (!securityProperties.deriveRuntimeAuthoritiesFromRole()) {
            return existing;
        }
        Set<String> merged = new LinkedHashSet<>(existing);
        String roleUpper = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        boolean adminLike = "ADMIN".equals(roleUpper) || existing.contains(PLATFORM_ROLE_ADMIN);
        if (adminLike) {
            merged.add(RuntimeAuthorities.VIEWER);
            merged.add(RuntimeAuthorities.OPERATOR);
            merged.add(RuntimeAuthorities.ADMIN);
        } else if ("OPERATOR".equals(roleUpper) || "BO_ADMIN".equals(roleUpper)) {
            merged.add(RuntimeAuthorities.VIEWER);
            merged.add(RuntimeAuthorities.OPERATOR);
        } else if ("USER".equals(roleUpper) || "VIEWER".equals(roleUpper)) {
            merged.add(RuntimeAuthorities.VIEWER);
        }
        return new ArrayList<>(merged);
    }
}
