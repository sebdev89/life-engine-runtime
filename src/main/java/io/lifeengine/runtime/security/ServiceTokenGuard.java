package io.lifeengine.runtime.security;

import io.jsonwebtoken.Claims;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Valida el contrato de los tokens service-to-service (KAN-173).
 *
 * <p>Se aplica <b>solo</b> cuando el token trae {@code token_use=service}. Los tokens de usuario no
 * llevan {@code iss} ni {@code aud} —nunca los llevaron— así que exigirles esos claims rechazaría
 * todas las sesiones abiertas. Por eso hay dos contratos y no uno más estricto para todos.
 *
 * <p>Y por eso mismo la ausencia de {@code token_use} <b>no</b> se interpreta como identidad de
 * servicio: si lo hiciera, cualquier token de usuario válido pasaría por los caminos S2S.
 */
@Component
public class ServiceTokenGuard {

    public static final String CLAIM_TOKEN_USE = "token_use";
    public static final String TOKEN_USE_SERVICE = "service";
    public static final String CLAIM_CLIENT_ID = "client_id";

    private final ServiceTokenProperties props;

    public ServiceTokenGuard(ServiceTokenProperties props) {
        this.props = props;
    }

    /** ¿Este token se presenta como credencial de servicio? */
    public static boolean isServiceToken(Claims claims) {
        Object use = claims.get(CLAIM_TOKEN_USE);
        return TOKEN_USE_SERVICE.equals(use);
    }

    /**
     * Verifica el contrato S2S sobre un token <b>cuya firma ya fue validada</b>.
     *
     * @param alg algoritmo leído del header. Se pasa desde afuera porque la decisión de rechazar
     *     HS256 tiene que tomarse acá y no depender de que el llamador se acuerde.
     * @return el motivo del rechazo, o vacío si el token cumple.
     */
    public Optional<String> validate(Claims claims, String alg, String kid) {
        // 1. Solo RS256. Un token S2S HS256 correctamente firmado con el JWT_SECRET histórico tiene
        //    que ser rechazado: si se aceptara, el secreto compartido seguiría alcanzando para
        //    hablar como Business Chat y todo KAN-173 sería decorativo.
        if (!"RS256".equals(alg)) {
            return Optional.of("s2s_alg_not_rs256");
        }
        // 2. kid presente y resuelto contra JWKS. Sin kid no se sabe qué clave usar, y aceptar
        //    "la única que haya" rompe la rotación de claves en silencio.
        if (kid == null || kid.isBlank()) {
            return Optional.of("s2s_missing_kid");
        }

        // 3. Issuer. Sin configurar → nada se acepta: preferible que los flujos S2S fallen a que
        //    acepten cualquier issuer porque falta una variable de entorno.
        String expectedIssuer = props.expectedIssuer();
        if (expectedIssuer == null || expectedIssuer.isBlank()) {
            return Optional.of("s2s_issuer_not_configured");
        }
        if (!expectedIssuer.equals(claims.getIssuer())) {
            return Optional.of("s2s_issuer_mismatch");
        }

        // 4. Audiencia: equivalencia EXACTA con el conjunto permitido, no "contiene".
        //    `aud` es una colección en el estándar JWT. Si se aceptara con `contains`, un token
        //    emitido con aud=[runtime, rag] pasaría en los dos servicios — que es exactamente el
        //    token universal que este diseño evita. Exigir el conjunto exacto {runtime} obliga a que
        //    el emisor haya acotado el token a este destino y a ninguno más.
        var aud = claims.getAudience();
        if (aud == null || aud.isEmpty()) {
            return Optional.of("s2s_missing_audience");
        }
        if (aud.size() != 1 || !aud.contains(props.expectedAudience())) {
            return Optional.of("s2s_audience_mismatch");
        }

        // 5. Sujeto y client_id, ambos contra listas cerradas.
        if (!allowed(props.allowedSubjects()).contains(claims.getSubject())) {
            return Optional.of("s2s_subject_not_allowed");
        }
        Object clientId = claims.get(CLAIM_CLIENT_ID);
        if (!(clientId instanceof String cid) || !allowed(props.allowedClientIds()).contains(cid)) {
            return Optional.of("s2s_client_id_not_allowed");
        }

        // 6. Authority mínima. `exp` e `iat` los valida la librería al parsear la firma; acá se
        //    verifica lo que la librería no sabe: que el token alcance para lo que viene a hacer.
        List<String> authorities = readAuthorities(claims);
        if (!authorities.contains(props.requiredAuthority())) {
            return Optional.of("s2s_insufficient_authority");
        }

        return Optional.empty();
    }

    /**
     * Principal para un token de servicio ya validado.
     *
     * <p>{@code userId} es un UUID derivado del sujeto de forma determinística. No hay una persona
     * detrás, pero {@link RuntimePrincipal} necesita un UUID y {@code UUID.fromString("service:…")}
     * explotaría. Derivarlo mantiene el identificador estable entre llamadas —sirve para correlacionar
     * en logs— sin inventar un usuario que no existe ni colisionar con UUIDs reales.
     */
    public static RuntimePrincipal toPrincipal(Claims claims) {
        String subject = claims.getSubject();
        UUID syntheticId = UUID.nameUUIDFromBytes(subject.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new RuntimePrincipal(
                syntheticId, subject, "SERVICE", List.copyOf(readAuthorities(claims)));
    }

    private static List<String> allowed(List<String> configured) {
        return configured == null ? List.of() : configured;
    }

    @SuppressWarnings("unchecked")
    private static List<String> readAuthorities(Claims claims) {
        Object raw = claims.get("authorities");
        if (raw instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }
}
