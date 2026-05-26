package io.lifeengine.runtime.tools;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ToolRegistry {

    private final Map<String, ToolExecutor> tools = new LinkedHashMap<>();

    public ToolRegistry(List<ToolExecutor> executors) {
        for (ToolExecutor executor : executors) {
            tools.put(executor.toolId(), executor);
        }
    }

    public void register(ToolExecutor executor) {
        tools.put(executor.toolId(), executor);
    }

    public ToolExecutor require(String toolId) {
        ToolExecutor executor = tools.get(toolId);
        if (executor == null) {
            throw new ToolNotFoundException(toolId);
        }
        return executor;
    }

    public Collection<ToolDefinition> definitions() {
        return tools.values().stream().map(ToolExecutor::definition).toList();
    }
}
