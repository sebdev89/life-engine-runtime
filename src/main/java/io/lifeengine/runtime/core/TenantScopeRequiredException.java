package io.lifeengine.runtime.core;

/**
 * El llamador está autenticado pero su token no afirma un tenant, y la operación pedida no se
 * puede scopear sin eso.
 *
 * <p>Es una denegación, no un error de validación: el request está bien formado y el token es
 * válido. Lo que falta es el claim que decide de quién son los datos. Se traduce a <b>403</b>,
 * siguiendo la semántica que fijó KAN-264 — una denegación devuelve 403 real, no 200 vacío.
 *
 * <p>Por qué no devolver una lista vacía: mentiría. Una lista vacía dice "este tenant no tiene
 * corridas"; acá lo que pasa es que no se sabe cuál es el tenant. Son dos cosas distintas y
 * confundirlas manda a alguien a debuggear datos cuando el problema es el token.
 */
public class TenantScopeRequiredException extends RuntimeException {

    public TenantScopeRequiredException(String message) {
        super(message);
    }
}
