package io.lifeengine.runtime.ext.businesschat.prompts;

import io.lifeengine.runtime.prompts.PromptTemplate;
import io.lifeengine.runtime.prompts.PromptTemplateRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * External prompt registry for business-chat.reply.v1 LLM templates.
 *
 * <p>Loads safe defaults, applies configuration overrides, and syncs active entries to
 * {@link PromptTemplateRegistry}. Agents keep using existing prompt ids — contract unchanged.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.business-chat.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BusinessPromptRegistry {

    private final Map<String, BusinessPromptEntry> entries = new LinkedHashMap<>();
    private final Map<String, String> activeVersionByKey = new LinkedHashMap<>();

    @Autowired
    public BusinessPromptRegistry(BusinessPromptRegistryProperties properties) {
        load(BusinessPromptDefaults.all());
        applyOverrides(properties == null ? BusinessPromptRegistryProperties.empty() : properties);
    }

    public String resolve(String promptKey) {
        Objects.requireNonNull(promptKey, "promptKey");
        String version = activeVersionByKey.get(promptKey);
        if (!StringUtils.hasText(version)) {
            version = BusinessPromptKeys.VERSION_V1;
        }
        BusinessPromptEntry entry = entries.get(compositeKey(promptKey, version));
        if (entry != null) {
            return entry.content();
        }
        return fallbackDefault(promptKey, version);
    }

    public BusinessPromptEntry requireActive(String promptKey) {
        String version = activeVersionByKey.get(promptKey);
        if (!StringUtils.hasText(version)) {
            throw new IllegalArgumentException("no active prompt for key: " + promptKey);
        }
        BusinessPromptEntry entry = entries.get(compositeKey(promptKey, version));
        if (entry == null) {
            throw new IllegalArgumentException("missing prompt entry: " + promptKey + "@" + version);
        }
        return entry;
    }

    public List<PromptTemplate> activeLlmTemplates() {
        List<PromptTemplate> templates = new ArrayList<>();
        for (Map.Entry<String, String> active : activeVersionByKey.entrySet()) {
            BusinessPromptEntry entry = entries.get(compositeKey(active.getKey(), active.getValue()));
            if (entry == null || !isLlmCategory(entry.category())) {
                continue;
            }
            templates.add(PromptTemplate.of(entry.promptKey(), entry.version(), entry.content()));
        }
        return List.copyOf(templates);
    }

    public void syncTo(PromptTemplateRegistry promptTemplateRegistry) {
        Objects.requireNonNull(promptTemplateRegistry, "promptTemplateRegistry");
        for (PromptTemplate template : activeLlmTemplates()) {
            promptTemplateRegistry.register(template);
        }
    }

    private void load(List<BusinessPromptEntry> defaults) {
        for (BusinessPromptEntry entry : defaults) {
            entries.put(entry.compositeKey(), entry);
            if (entry.active()) {
                activeVersionByKey.put(entry.promptKey(), entry.version());
            }
        }
    }

    private void applyOverrides(BusinessPromptRegistryProperties properties) {
        if (properties == null || properties.overrides().isEmpty()) {
            return;
        }
        for (BusinessPromptRegistryProperties.OverrideEntry override : properties.overrides()) {
            if (override == null || !StringUtils.hasText(override.promptKey()) || !StringUtils.hasText(override.content())) {
                continue;
            }
            String version =
                    StringUtils.hasText(override.version())
                            ? override.version().trim()
                            : BusinessPromptKeys.VERSION_V1;
            BusinessPromptCategory category = parseCategory(override.category(), override.promptKey());
            boolean active = override.active() == null || override.active();
            BusinessPromptEntry entry =
                    new BusinessPromptEntry(
                            override.promptKey().trim(), version, active, category, override.content());
            entries.put(entry.compositeKey(), entry);
            if (active) {
                deactivateOtherVersions(entry.promptKey(), entry.version());
                activeVersionByKey.put(entry.promptKey(), entry.version());
            }
        }
    }

    private void deactivateOtherVersions(String promptKey, String activeVersion) {
        for (BusinessPromptEntry existing : List.copyOf(entries.values())) {
            if (!existing.promptKey().equals(promptKey)) {
                continue;
            }
            if (existing.version().equals(activeVersion)) {
                continue;
            }
            entries.put(
                    existing.compositeKey(),
                    new BusinessPromptEntry(
                            existing.promptKey(),
                            existing.version(),
                            false,
                            existing.category(),
                            existing.content()));
        }
    }

    private static boolean isLlmCategory(BusinessPromptCategory category) {
        return category == BusinessPromptCategory.INTENT_DETECTION
                || category == BusinessPromptCategory.REPLY_GENERATION
                || category == BusinessPromptCategory.LEAD_CAPTURE
                || category == BusinessPromptCategory.SYSTEM;
    }

    private static BusinessPromptCategory parseCategory(String raw, String promptKey) {
        if (!StringUtils.hasText(raw)) {
            return categoryForKey(promptKey);
        }
        return BusinessPromptCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    private static BusinessPromptCategory categoryForKey(String promptKey) {
        return switch (promptKey) {
            case BusinessPromptKeys.INTENT_DETECTION -> BusinessPromptCategory.INTENT_DETECTION;
            case BusinessPromptKeys.REPLY_GENERATION -> BusinessPromptCategory.REPLY_GENERATION;
            case BusinessPromptKeys.LEAD_CAPTURE -> BusinessPromptCategory.LEAD_CAPTURE;
            default -> BusinessPromptCategory.SYSTEM;
        };
    }

    private static String fallbackDefault(String promptKey, String version) {
        for (BusinessPromptEntry entry : BusinessPromptDefaults.all()) {
            if (entry.promptKey().equals(promptKey) && entry.version().equals(version)) {
                return entry.content();
            }
        }
        throw new IllegalArgumentException("unknown prompt key: " + promptKey);
    }

    private static String compositeKey(String promptKey, String version) {
        return promptKey + "@" + version;
    }
}
