package io.lifeengine.runtime.ext.businesschat;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Disabled in-memory conversation store (H2 in {@code StabilizationAudit.md}).
 *
 * <p>Historically this Spring bean kept the last N customer/bot exchanges per
 * conversation in a process-local {@code ConcurrentHashMap}. That worked when
 * the Runtime was the only path holding conversation memory, but the
 * production caller (business-chat-service / Postgres) now owns the
 * authoritative transcript and ships {@code conversationHistory} on every
 * run. The in-memory copy therefore:
 *
 * <ul>
 *   <li>has zero effect when {@code conversationHistory} is present in the
 *       input (BC's production behavior) — the stored entries are never
 *       consulted;</li>
 *   <li>silently desynchronizes from Postgres when both paths run (e.g. two
 *       Runtime replicas behind a load balancer where only one served the
 *       previous turn);</li>
 *   <li>leaks memory across tenants by mixing arbitrary {@code conversationId}
 *       strings in a single map with no eviction policy beyond
 *       per-conversation truncation.</li>
 * </ul>
 *
 * <p>The class is kept as a no-op bean so {@link
 * io.lifeengine.runtime.ext.businesschat.stages.BusinessContextAgent} and
 * {@link io.lifeengine.runtime.ext.businesschat.stages.BusinessReplyAgent}
 * continue to compile without their constructor signatures changing —
 * {@link #append(String, String, String)} is a no-op and {@link
 * #history(String)} always returns an empty list. When
 * {@code BusinessContextAgent.resolveConversationHistory} falls back to
 * {@link #history(String)} (i.e. when the caller forgot to pass
 * {@code conversationHistory}), the Runtime now correctly treats the
 * conversation as having no prior context — exactly the behavior the
 * stateless workflow contract describes.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.business-chat.enabled",
        havingValue = "true",
        matchIfMissing = true)
@Deprecated(forRemoval = true)
public class BusinessConversationContext {

    /** Retained for binary compatibility; no longer used as eviction threshold. */
    public static final int MAX_INTERACTIONS = 0;

    public record Interaction(String customerMessage, String botResponse) {}

    /** Always returns an empty list. The authoritative transcript lives in business-chat-service. */
    public List<Interaction> history(String conversationId) {
        return List.of();
    }

    /**
     * No-op. Conversation persistence now lives in business-chat-service
     * ({@code MessageR2dbcStore}) and is the only source of truth for
     * subsequent runs.
     */
    public void append(String conversationId, String customerMessage, String botResponse) {
        // Intentionally empty — see class Javadoc.
    }
}
