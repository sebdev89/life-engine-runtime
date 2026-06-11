package io.lifeengine.runtime.ext.businesschat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-memory conversation history keyed by {@code conversationId}.
 *
 * <p>Retains the last {@link #MAX_INTERACTIONS} customer/bot exchanges per conversation. Database
 * backing can replace this store later without changing agent code.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.business-chat.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BusinessConversationContext {

    public static final int MAX_INTERACTIONS = 10;

    public record Interaction(String customerMessage, String botResponse) {}

    private final ConcurrentMap<String, Deque<Interaction>> conversations = new ConcurrentHashMap<>();

    public List<Interaction> history(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        Deque<Interaction> deque = conversations.get(conversationId.trim());
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return List.copyOf(deque);
        }
    }

    public void append(String conversationId, String customerMessage, String botResponse) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(customerMessage, "customerMessage");
        Objects.requireNonNull(botResponse, "botResponse");
        if (conversationId.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: conversationId");
        }
        if (customerMessage.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: customerMessage");
        }
        if (botResponse.isBlank()) {
            throw new IllegalArgumentException("missing or empty field: botResponse");
        }

        Deque<Interaction> deque =
                conversations.computeIfAbsent(conversationId.trim(), ignored -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(new Interaction(customerMessage.trim(), botResponse.trim()));
            while (deque.size() > MAX_INTERACTIONS) {
                deque.removeFirst();
            }
        }
    }
}
