package io.lifeengine.runtime.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Contrato que debe cumplir un token service-to-service para ser aceptado (KAN-173).
 *
 * <p>Todo lo de acá viaja por configuración, nunca hardcodeado: el issuer y la audiencia cambian por
 * ambiente, y un token emitido por el Auth de UAT no debe servir en prod aunque la firma sea válida.
 *
 * <p>Los defaults son deliberadamente vacíos. {@link ServiceTokenGuard} rechaza cualquier token de
 * servicio si el issuer esperado está sin configurar: es preferible que los flujos S2S fallen a que
 * acepten cualquier issuer porque alguien olvidó una variable.
 */
// El prefijo tiene que coincidir con dónde vive el bloque en application.yml. Estaba en
// "lifeengine.security.service-tokens" mientras el YAML lo declaraba bajo
// lifeengine.runtime.security: las properties no bindeaban, expectedIssuer quedaba vacío, y
// TODOS los tokens de servicio se rechazaban con s2s_issuer_not_configured (KAN-173).
@ConfigurationProperties(prefix = "lifeengine.runtime.security.service-tokens")
public record ServiceTokenProperties(
        /**
         * Issuer exigido en el claim {@code iss}. Vacío = ningún token de servicio se acepta.
         */
        @DefaultValue("") String expectedIssuer,

        /**
         * La ÚNICA audiencia válida para este servicio. En Runtime es {@code runtime}: un token
         * emitido para RAG tiene que ser rechazado acá aunque venga del mismo Auth y esté bien
         * firmado. Es lo que evita que comprometer un destino sirva para alcanzar el otro.
         */
        @DefaultValue("runtime") String expectedAudience,

        /**
         * Sujetos aceptados, en la forma {@code service:<client-id>}. Lista cerrada: un servicio
         * nuevo tiene que declararse explícitamente, no alcanzarle con tener una firma válida.
         */
        @DefaultValue("service:business-chat") List<String> allowedSubjects,

        /**
         * {@code client_id} aceptados. Redundante con {@code allowedSubjects} a propósito: son dos
         * claims distintos que el emisor setea por separado, y exigir que ambos coincidan detecta un
         * token manipulado o mal emitido.
         */
        @DefaultValue("business-chat") List<String> allowedClientIds,

        /**
         * Authority mínima que debe traer el token para los flujos que invoca Business Chat.
         *
         * <p>Business Chat llama a {@code /api/runtime/**} (arrancar runs y agregar eventos), que
         * exige {@code RUNTIME_OPERATOR}. Se pide esa y no {@code RUNTIME_ADMIN}: la authority
         * administrativa habilita operaciones que Business Chat nunca necesita, y darla "por las
         * dudas" convierte un compromiso de Business Chat en control total del Runtime.
         */
        @DefaultValue("RUNTIME_OPERATOR") String requiredAuthority) {}
