package io.lifeengine.runtime.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Spans de corrida y de etapa.
 *
 * <p><b>Por qué {@link Observation} y no {@code Tracer} directo.</b> Antes esto llamaba a
 * {@code tracer.nextSpan()}, que toma el padre del span vigente en el <em>ThreadLocal</em>. En una
 * cadena reactiva que salta de hilo eso no existe: cada span arrancaba sin padre y salía como una
 * traza raíz nueva. Medido contra el cluster, un solo {@code POST /api/runtime/runs} producía dos
 * trazas distintas —el pedido por un lado, {@code runtime.run.execute} por el otro— y "quién pidió
 * esto" quedaba del otro lado de una frontera que ninguna consulta cruza.
 *
 * <p>La {@code Observation} se guarda en el <b>Reactor Context</b>, que sí viaja con la cadena. De
 * ahí sale el padre. Y es además la clave que mira la instrumentación de {@code WebClient} de
 * Spring, así que las llamadas salientes al LLM se cuelgan solas del span de la etapa que las hace,
 * sin que haya que pasarles nada.
 */
@Component
public class RuntimeObservation {

    private final ObservationRegistry registry;

    public RuntimeObservation(ObservationRegistry registry) {
        this.registry = registry;
    }

    /**
     * La observación vigente en <em>este</em> hilo, para capturarla antes de saltar a otro.
     *
     * <p>La usa el executor: cuando agenda la corrida sigue estando adentro del pedido HTTP, y ese
     * es el único momento en que el padre está al alcance. Un instante después, sobre otro hilo y
     * con un contexto vacío, ya no hay de dónde sacarlo.
     */
    public Observation currentObservation() {
        return registry.getCurrentObservation();
    }

    public <T> Mono<T> observeRun(
            String workflowId, String runId, String correlationId, Mono<T> execution) {
        return observe(
                "runtime.run.execute",
                execution,
                new String[] {"workflow.id", workflowId},
                new String[] {"run.id", runId, "correlation.id", correlationId});
    }

    public <T> Mono<T> observeStage(
            String workflowId, String runId, String stageId, String stageType, Mono<T> execution) {
        return observe(
                "runtime.stage.execute",
                execution,
                new String[] {"workflow.id", workflowId, "stage.type", stageType},
                new String[] {"run.id", runId, "stage.id", stageId});
    }

    /**
     * @param bajaCardinalidad valores de un conjunto chico y cerrado. Van también a las métricas:
     *     cada combinación distinta es una serie temporal más.
     * @param altaCardinalidad valores únicos por corrida ({@code run.id}, {@code correlation.id}).
     *     Van SÓLO a la traza. Meterlos como baja cardinalidad crearía una serie de Prometheus por
     *     corrida, que es la forma más rápida conocida de voltear un Prometheus.
     */
    private <T> Mono<T> observe(
            String name, Mono<T> execution, String[] bajaCardinalidad, String[] altaCardinalidad) {
        return Mono.deferContextual(
                view -> {
                    Observation observacion = Observation.createNotStarted(name, registry);

                    // El padre sale del Context, no del ThreadLocal. Si no hay ninguno —una corrida
                    // que nadie originó por HTTP— el span queda raíz, que es lo correcto.
                    Observation padre = view.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
                    if (padre != null) {
                        observacion.parentObservation(padre);
                    }

                    for (int i = 0; i < bajaCardinalidad.length; i += 2) {
                        observacion.lowCardinalityKeyValue(
                                bajaCardinalidad[i], nonNull(bajaCardinalidad[i + 1]));
                    }
                    for (int i = 0; i < altaCardinalidad.length; i += 2) {
                        observacion.highCardinalityKeyValue(
                                altaCardinalidad[i], nonNull(altaCardinalidad[i + 1]));
                    }
                    observacion.start();

                    return execution
                            .doOnError(observacion::error)
                            .doFinally(sig -> observacion.stop())
                            // Hacia arriba, para que lo que corra ADENTRO de esta ejecución
                            // —las etapas, y el WebClient de cada etapa— encuentre este span
                            // como padre.
                            .contextWrite(
                                    ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observacion));
                });
    }

    /** Micrometer rechaza un valor de tag nulo; un campo ausente degrada a "unknown". */
    private static String nonNull(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
