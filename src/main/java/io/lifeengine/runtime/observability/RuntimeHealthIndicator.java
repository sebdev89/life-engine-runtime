package io.lifeengine.runtime.observability;

import io.lifeengine.runtime.core.RunStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class RuntimeHealthIndicator implements ReactiveHealthIndicator {

    private final RunStore store;

    public RuntimeHealthIndicator(RunStore store) {
        this.store = store;
    }

    @Override
    public Mono<Health> health() {
        return Mono.just(
                Health.up()
                        .withDetail("store", store.getClass().getSimpleName())
                        .withDetail("component", "life-engine-runtime")
                        .build());
    }
}
