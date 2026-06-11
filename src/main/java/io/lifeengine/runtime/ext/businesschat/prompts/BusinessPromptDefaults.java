package io.lifeengine.runtime.ext.businesschat.prompts;

import io.lifeengine.runtime.ext.businesschat.BusinessChatReplyPrompts;
import io.lifeengine.runtime.prompts.PromptTemplate;
import java.util.List;

/** Safe built-in defaults — used when no override is configured or active. */
public final class BusinessPromptDefaults {

    private BusinessPromptDefaults() {}

    public static List<BusinessPromptEntry> all() {
        return List.of(
                fromTemplate(BusinessPromptCategory.INTENT_DETECTION, BusinessChatReplyPrompts.context()),
                fromTemplate(BusinessPromptCategory.REPLY_GENERATION, BusinessChatReplyPrompts.reply()),
                fromTemplate(BusinessPromptCategory.LEAD_CAPTURE, BusinessChatReplyPrompts.leadCapture()));
    }

    private static BusinessPromptEntry fromTemplate(BusinessPromptCategory category, PromptTemplate template) {
        return new BusinessPromptEntry(
                template.id(), template.version(), true, category, template.systemMessage());
    }
}
