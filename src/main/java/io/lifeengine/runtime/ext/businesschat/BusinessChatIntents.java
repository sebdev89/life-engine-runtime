package io.lifeengine.runtime.ext.businesschat;

import java.util.List;

/** Allowed customer-intent values for the {@code business-chat.reply.v1} workflow. */
public final class BusinessChatIntents {

    public static final List<String> ALL =
            List.of(
                    "greeting",
                    "pricing",
                    "booking",
                    "location",
                    "schedule",
                    "support",
                    "complaint",
                    "human_handoff",
                    "out_of_domain",
                    "unclear",
                    "abusive",
                    "emergency",
                    "legal_sensitive");

    public static final List<String> GUARDRAIL =
            List.of("out_of_domain", "unclear", "abusive", "emergency", "legal_sensitive");

    static final String PROMPT_ENUM = String.join(" | ", ALL);

    static final String CLASSIFICATION_GUIDE =
            """
            Intent guide:
            - greeting: hello, hi, good morning, or opening small talk without a concrete request.
            - pricing: prices, costs, fees, or "how much" questions.
            - booking: appointments, reservations, turnos, or scheduling a visit.
            - location: address, neighborhood, directions, or "where are you".
            - schedule: opening hours, availability windows, or "when are you open".
            - support: general service questions not covered by pricing, booking, location, or schedule.
            - complaint: dissatisfaction, bad experience, or service problems.
            - human_handoff: explicit request to speak with a person or agent.
            - out_of_domain: topics unrelated to the business (sports trivia, general knowledge,
              medical prescriptions, weather, entertainment) that are not in the knowledge base.
            - unclear: gibberish, too vague, or unintelligible message with no discernible request.
            - abusive: insults, harassment, or hostile language toward the bot or staff.
            - emergency: urgent risk, violence, self-harm, or immediate danger signals.
            - legal_sensitive: requests for binding legal advice on whether to sue, file charges,
              or take legal action on a personal case (not general service information).
            """
                    .strip();

    private BusinessChatIntents() {}
}
