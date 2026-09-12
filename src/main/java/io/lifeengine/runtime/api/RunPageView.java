package io.lifeengine.runtime.api;

import java.util.List;

/**
 * Una página de corridas.
 *
 * <p>{@code nextCursor} es {@code null} cuando la página vino incompleta, que es la señal de que
 * no hay más. Si el total es múltiplo exacto del límite, el llamador va a pedir una página más y
 * recibir una vacía: es el costo conocido de no contar filas de antemano, y contarlas costaría un
 * {@code COUNT(*)} en cada request para ahorrar un viaje en un caso de borde.
 *
 * <p>Sin {@code total} a propósito, por lo mismo.
 */
public record RunPageView(List<RunSummaryView> runs, String nextCursor) {}
