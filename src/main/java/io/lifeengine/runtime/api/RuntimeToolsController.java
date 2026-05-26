package io.lifeengine.runtime.api;

import io.lifeengine.runtime.tools.ToolDefinition;
import io.lifeengine.runtime.tools.ToolRegistry;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/runtime/tools")
public class RuntimeToolsController {

    private final ToolRegistry toolRegistry;

    public RuntimeToolsController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    public Mono<List<ToolListView>> listTools() {
        return Mono.fromCallable(
                () ->
                        toolRegistry.definitions().stream()
                                .map(RuntimeToolsController::toView)
                                .toList());
    }

    private static ToolListView toView(ToolDefinition definition) {
        return new ToolListView(definition.toolId(), definition.description());
    }

    public record ToolListView(String toolId, String description) {}
}
