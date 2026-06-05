package io.lifeengine.runtime.observability;

import io.lifeengine.runtime.core.RunStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Reactive health indicator surfaced under {@code /actuator/health/runtime}.
 *
 * <p>Returns the active {@link RunStore} implementation so operators can tell at a glance
 * whether the runtime is talking to PostgreSQL ({@code R2dbcRunStore}) or the volatile
 * in-memory fallback ({@code InMemoryRunStore}). The check is intentionally cheap — Spring
 * Boot's own {@code db}/{@code r2dbc} indicators already cover connectivity probing; this
 * indicator is the runtime-shaped story (which store backs the run history).
 */
@Component
public class RuntimeHealthIndicator implements ReactiveHealthIndicator {

    private final RunStore store;

    public RuntimeHealthIndicator(RunStore store) {
        this.store = store;
    }

    @Override
    public Mono<Health> health() {
        return Mono.fromSupplier(this::buildHealth)
                // Cheap synchronous build, but keep store.getClass() off the Netty
                // event-loop for consistency with every other RunStore touchpoint.
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Health buildHealth() {
        String storeName = store.getClass().getSimpleName();
        return Health.up()
                .withDetail("component", "life-engine-runtime")
                .withDetail("store", storeName)
                .withDetail("persistence", storeName.startsWith("R2dbc") ? "r2dbc-postgres" : "in-memory")
                .build();
    }
}
