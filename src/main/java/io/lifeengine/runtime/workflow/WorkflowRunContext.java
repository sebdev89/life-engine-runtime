package io.lifeengine.runtime.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.api.SecretRedactor;
import io.lifeengine.runtime.core.RunStore;
import io.lifeengine.runtime.domain.AgentStageRecord;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.events.RunEventPublisher;
import io.lifeengine.runtime.llm.LlmCallException;
import io.lifeengine.runtime.llm.LlmCallRecord;
import io.lifeengine.runtime.prompts.PromptTemplate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Context pack for one workflow run: input, correlation, stage/agent/tool outputs, LLM records, warnings.
 */
public final class WorkflowRunContext {

    public static final String DEFAULT_INPUT =
            "[INCIDENT] CPU saturation exceeded 92% for 8 minutes on node-3 (prod-compute)."
                    + " [ACTION REQUIRED] Review horizontal scaling policy and deployments from the last 2 hours.";

    public static final String LLM_PROVIDER = "openai-compatible";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final UUID runId;
    private final String workflowId;
    private final String correlationId;
    private final String input;
    private final RunStore store;
    private final RunEventPublisher publisher;
    private final AtomicBoolean cancelled;
    private final Map<String, String> stageOutputs = new ConcurrentHashMap<>();
    private final Map<String, String> agentOutputs = new ConcurrentHashMap<>();
    private final Map<String, String> toolOutputs = new ConcurrentHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public WorkflowRunContext(
            UUID runId,
            String workflowId,
            String correlationId,
            String input,
            RunStore store,
            RunEventPublisher publisher,
            AtomicBoolean cancelled) {
        this.runId = runId;
        this.workflowId = workflowId;
        this.correlationId = correlationId;
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

    public String correlationId() {
        return correlationId;
    }

    public String input() {
        return input;
    }

    public Map<String, String> stageOutputs() {
        return Map.copyOf(stageOutputs);
    }

    public Map<String, String> agentOutputs() {
        return Map.copyOf(agentOutputs);
    }

    public Map<String, String> toolOutputs() {
        return Map.copyOf(toolOutputs);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void putStageOutput(String stageId, String output) {
        stageOutputs.put(stageId, output);
    }

    public void putAgentOutput(String agentId, String output) {
        agentOutputs.put(agentId, output);
    }

    public void putToolOutput(String toolId, String output) {
        toolOutputs.put(toolId, output);
    }

    public void appendLlmCallRecord(LlmCallRecord record) {
        store.appendLlmCallRecord(runId, record);
    }

    public void recordStage(AgentStageRecord stage) {
        store.appendAgentStage(runId, stage);
    }

    public void addWarning(String warning) {
        warnings.add(warning);
        emit(EventType.WARNING_RECORDED, Map.of("message", truncate(warning, 500)), false);
    }

    public void emit(EventType type, Map<String, String> attributes, boolean terminal) {
        emit(type.wireName(), attributes, terminal);
    }

    public void emit(String type, Map<String, String> attributes, boolean terminal) {
        Map<String, String> enriched = new LinkedHashMap<>();
        enriched.put("workflowId", workflowId);
        enriched.put("correlationId", correlationId);
        if (attributes != null) {
            enriched.putAll(attributes);
        }
        RuntimeEvent event = RuntimeEvent.of(runId, type, enriched, terminal);
        store.appendEvent(event);
        publisher.publish(event);
    }

    public static Map<String, String> stageAttrs(WorkflowStage stage) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("stageId", stage.stageId());
        attrs.put("stageType", stage.kind().name());
        attrs.put("order", Integer.toString(stage.order()));
        attrs.put("refId", stage.refId());
        return attrs;
    }

    public static Map<String, String> previewAttrs(String agentId, String model, String promptPreview) {
        return previewAttrs(agentId, model, promptPreview, null);
    }

    public static Map<String, String> previewAttrs(
            String agentId, String model, String promptPreview, PromptTemplate template) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("agentId", agentId);
        attrs.put("model", model);
        attrs.put("promptPreview", truncate(SecretRedactor.redact(promptPreview), 240));
        attrs.put("startedAt", Instant.now().toString());
        applyPromptTemplate(attrs, template);
        return attrs;
    }

    public static Map<String, String> llmSucceededAttrs(
            String agentId, String model, long latencyMs, String responsePreview, Map<String, Object> usage) {
        return llmSucceededAttrs(agentId, model, latencyMs, responsePreview, usage, null);
    }

    public static Map<String, String> llmSucceededAttrs(
            String agentId,
            String model,
            long latencyMs,
            String responsePreview,
            Map<String, Object> usage,
            PromptTemplate template) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("agentId", agentId);
        attrs.put("model", model);
        attrs.put("latencyMs", Long.toString(latencyMs));
        attrs.put("responsePreview", truncate(SecretRedactor.redact(responsePreview), 240));
        attrs.put("usage", usageJson(usage));
        applyPromptTemplate(attrs, template);
        return attrs;
    }

    private static void applyPromptTemplate(Map<String, String> attrs, PromptTemplate template) {
        if (template == null) {
            return;
        }
        attrs.put("promptTemplateId", template.id());
        attrs.put("promptTemplateVersion", template.version());
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
            attrs.put(
                    "responseBodyPreview",
                    truncate(SecretRedactor.redact(ex.responseBody()), 4000));
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
