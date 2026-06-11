package io.lifeengine.runtime.ext.businesschat.prompts;

/** Stable prompt keys for business-chat.reply.v1 (aligned with PromptTemplate ids). */
public final class BusinessPromptKeys {

    public static final String VERSION_V1 = "v1";

    public static final String INTENT_DETECTION = "business-chat.reply.context";
    public static final String REPLY_GENERATION = "business-chat.reply.generate";
    public static final String LEAD_CAPTURE = "business-chat.reply.lead-capture";

    private BusinessPromptKeys() {}
}
