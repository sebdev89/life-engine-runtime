package io.lifeengine.runtime.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.core.InMemoryRunStore;
import io.lifeengine.runtime.llm.LlmCallException;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.events.RunEventPublisher;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-run workflow execution context: input, cancellation, event emission. */
public final class WorkflowRunContext {

    public static final String LLM_WORKFLOW_ID = "demo.llm.workflow";
    public static final String NO_LLM_WORKFLOW_ID = "demo.no-llm.workflow";
    public static final String DEFAULT_INPUT =
            "Life Engine runtime observability check: summarize and classify this message.";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final UUID runId;
    private final String workflowId;
    private final String input;
    private final InMemoryRunStore store;
    private final RunEventPublisher publisher;
    private final AtomicBoolean cancelled;

    public WorkflowRunContext(
            UUID runId,
            String workflowId,
            String input,
            InMemoryRunStore store,
            RunEventPublisher publisher,
            AtomicBoolean cancelled) {
        this.runId = runId;
        this.workflowId = workflowId;
        this.input = input;
        this.store = store;
        this.publisher = publisher;
        this.cancelled = cancelled;
    }

    public UUID runId() {
        return runId;
    }

    public String workflowId() {
        return workflowId;
    }

    public String input() {
        return input;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void emit(String type, Map<String, String> attributes, boolean terminal) {
        RuntimeEvent event = RuntimeEvent.of(runId, type, attributes, terminal);
        store.appendEvent(event);
        publisher.publish(event);
    }

    public static Map<String, String> previewAttrs(String agentId, String model, String promptPreview) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("agentId", agentId);
        attrs.put("model", model);
        attrs.put("promptPreview", truncate(promptPreview, 240));
        attrs.put("startedAt", Instant.now().toString());
        return attrs;
    }

    public static Map<String, String> llmCompletedAttrs(
            String agentId,
            String model,
            long latencyMs,
            String responsePreview,
            Map<String, Object> usage) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("agentId", agentId);
        attrs.put("model", model);
        attrs.put("latencyMs", Long.toString(latencyMs));
        attrs.put("responsePreview", truncate(responsePreview, 240));
        attrs.put("usage", usageJson(usage));
        return attrs;
    }

    public static Map<String, String> llmFailedAttrs(
            String agentId, String model, String error, long latencyMs) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("agentId", agentId);
        attrs.put("model", model);
        attrs.put("error", truncate(error, 500));
        attrs.put("latencyMs", Long.toString(latencyMs));
        return attrs;
    }

    public static Map<String, String> llmFailedAttrs(
            String agentId, String model, long latencyMs, LlmCallException ex) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("agentId", agentId);
        attrs.put("model", model != null && !model.isBlank() ? model : nullToEmpty(ex.model()));
        attrs.put("error", truncate(ex.getMessage(), 500));
        attrs.put("latencyMs", Long.toString(latencyMs));
        if (ex.statusCode() != null) {
            attrs.put("statusCode", Integer.toString(ex.statusCode()));
        }
        if (ex.endpoint() != null && !ex.endpoint().isBlank()) {
            attrs.put("endpoint", ex.endpoint());
        }
        if (ex.responseBody() != null && !ex.responseBody().isBlank()) {
            attrs.put("responseBodyPreview", truncate(ex.responseBody(), 4000));
        }
        if (ex.requestSummary() != null && !ex.requestSummary().isBlank()) {
            attrs.put("requestSummary", truncate(ex.requestSummary(), 1000));
        }
        return attrs;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String usageJson(Map<String, Object> usage) {
        try {
            return JSON.writeValueAsString(usage == null ? Map.of() : usage);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    public static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }
}
