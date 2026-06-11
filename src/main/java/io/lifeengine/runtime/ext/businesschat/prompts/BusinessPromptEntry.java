package io.lifeengine.runtime.ext.businesschat.prompts;

import org.springframework.util.StringUtils;

/** Versioned prompt template entry in {@link BusinessPromptRegistry}. */
public record BusinessPromptEntry(
        String promptKey,
        String version,
        boolean active,
        BusinessPromptCategory category,
        String content) {

    public BusinessPromptEntry {
        if (!StringUtils.hasText(promptKey)) {
            throw new IllegalArgumentException("missing promptKey");
        }
        if (!StringUtils.hasText(version)) {
            throw new IllegalArgumentException("missing version");
        }
        if (category == null) {
            throw new IllegalArgumentException("missing category");
        }
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("missing content");
        }
        promptKey = promptKey.trim();
        version = version.trim();
        content = content.strip();
    }

    public String compositeKey() {
        return promptKey + "@" + version;
    }
}
