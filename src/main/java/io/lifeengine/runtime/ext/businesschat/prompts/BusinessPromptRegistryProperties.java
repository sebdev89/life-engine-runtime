package io.lifeengine.runtime.ext.businesschat.prompts;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lifeengine.runtime.ext.business-chat.prompts")
public record BusinessPromptRegistryProperties(List<OverrideEntry> overrides) {

    public BusinessPromptRegistryProperties {
        overrides = overrides == null ? List.of() : List.copyOf(overrides);
    }

    public static BusinessPromptRegistryProperties empty() {
        return new BusinessPromptRegistryProperties(List.of());
    }

    public record OverrideEntry(
            String promptKey,
            String version,
            Boolean active,
            String category,
            String content) {}
}
