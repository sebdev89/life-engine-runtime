package io.lifeengine.runtime.prompts;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * Minimal in-memory registry of {@link PromptTemplate}s keyed by {@code id@version}.
 *
 * <p>Designed to be populated at bootstrap time by {@code RuntimeModule} implementations and
 * consulted by LLM-backed agents to fetch their system prompt while emitting only template
 * {@code id}/{@code version} metadata on runtime events (never the full prompt text).
 */
@Component
public class PromptTemplateRegistry {

    private final ConcurrentMap<String, PromptTemplate> templates = new ConcurrentHashMap<>();

    public void register(PromptTemplate template) {
        Objects.requireNonNull(template, "template");
        templates.put(key(template.id(), template.version()), template);
    }

    public PromptTemplate require(String id, String version) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        PromptTemplate template = templates.get(key(id, version));
        if (template == null) {
            throw new PromptTemplateNotFoundException(id, version);
        }
        return template;
    }

    public Collection<PromptTemplate> all() {
        return List.copyOf(templates.values());
    }

    Map<String, PromptTemplate> snapshot() {
        return Map.copyOf(templates);
    }

    private static String key(String id, String version) {
        return id + "@" + version;
    }
}
