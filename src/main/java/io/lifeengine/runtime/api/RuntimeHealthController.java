package io.lifeengine.runtime.api;

import io.lifeengine.runtime.core.RunStore;
import io.lifeengine.runtime.llm.LlmClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Public runtime health (no auth); complements {@code /actuator/health}. */
@RestController
@RequestMapping("/api/runtime")
public class RuntimeHealthController {

    private final RunStore store;
    private final LlmClient llmClient;

    public RuntimeHealthController(RunStore store, LlmClient llmClient) {
        this.store = store;
        this.llmClient = llmClient;
    }

    @GetMapping("/health")
    public Mono<Map<String, Object>> health() {
        return llmClient
                .health()
                .map(
                        llmUp -> {
                            Map<String, Object> body = new LinkedHashMap<>();
                            body.put("status", "UP");
                            body.put("component", "life-engine-runtime");
                            body.put("store", store.getClass().getSimpleName());
                            body.put("llmReachable", llmUp);
                            return body;
                        })
                .onErrorResume(
                        ex ->
                                Mono.just(
                                        Map.of(
                                                "status",
                                                "UP",
                                                "component",
                                                "life-engine-runtime",
                                                "store",
                                                store.getClass().getSimpleName(),
                                                "llmReachable",
                                                false,
                                                "llmProbeError",
                                                ex.getMessage())));
    }
}
