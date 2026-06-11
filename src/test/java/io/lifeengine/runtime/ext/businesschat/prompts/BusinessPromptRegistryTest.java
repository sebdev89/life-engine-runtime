package io.lifeengine.runtime.ext.businesschat.prompts;

import io.lifeengine.runtime.ext.businesschat.BusinessChatReplyPrompts;
import io.lifeengine.runtime.prompts.PromptTemplateRegistry;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class BusinessPromptRegistryTest {

    @Test
    void configOverrideChangesReplyPromptWithoutChangingPromptKey() {
        BusinessPromptRegistryProperties properties =
                new BusinessPromptRegistryProperties(
                        java.util.List.of(
                                new BusinessPromptRegistryProperties.OverrideEntry(
                                        BusinessPromptKeys.REPLY_GENERATION,
                                        BusinessPromptKeys.VERSION_V1,
                                        true,
                                        "REPLY_GENERATION",
                                        "OVERRIDE_REPLY_PROMPT_MARKER: reply agent custom body.")));
        BusinessPromptRegistry registry = new BusinessPromptRegistry(properties);

        Assertions.assertThat(registry.resolve(BusinessPromptKeys.REPLY_GENERATION))
                .contains("OVERRIDE_REPLY_PROMPT_MARKER");
    }

    @Test
    void syncToPromptTemplateRegistry_registersActiveTemplates() {
        BusinessPromptRegistry registry = new BusinessPromptRegistry(BusinessPromptRegistryProperties.empty());
        PromptTemplateRegistry promptTemplateRegistry = new PromptTemplateRegistry();
        registry.syncTo(promptTemplateRegistry);

        Assertions.assertThat(
                        promptTemplateRegistry
                                .require(
                                        BusinessChatReplyPrompts.REPLY_ID,
                                        BusinessChatReplyPrompts.VERSION_V1)
                                .systemMessage())
                .contains("reply agent");
    }

    @Test
    void inactiveVersionFallsBackToActiveDefault() {
        BusinessPromptRegistry registry = new BusinessPromptRegistry(BusinessPromptRegistryProperties.empty());

        String baseline = registry.resolve(BusinessPromptKeys.INTENT_DETECTION);
        Assertions.assertThat(baseline).contains("context agent");
    }
}
