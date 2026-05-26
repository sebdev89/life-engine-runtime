package io.lifeengine.runtime.api;

import io.lifeengine.runtime.domain.AgentStageRecord;
import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.llm.LlmCallRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds canonical {@link RunDetailView} for replay / SSE rehydration. */
public final class RunDetailAssembler {

    private RunDetailAssembler() {}

    public static RunDetailView assemble(
            Run run, List<AgentStageRecord> agentStages, List<LlmCallRecord> llmCalls, List<RuntimeEvent> events) {
        List<String> warnings = extractWarnings(run, events);
        String terminalError = extractTerminalError(run, events);
        return new RunDetailView(
                RunResponse.from(run, RunResponse.from(run).message(), warnings),
                agentStages.stream().map(AgentStageView::from).toList(),
                mapLlmCalls(llmCalls, events),
                events.stream().map(RuntimeEventResponse::from).toList(),
                terminalError);
    }

    static List<LlmCallView> mapLlmCalls(List<LlmCallRecord> records, List<RuntimeEvent> events) {
        Map<String, String> parseErrors = new HashMap<>();
        Map<String, String> parsedResponses = new HashMap<>();
        for (RuntimeEvent event : events) {
            String agentId = event.attributes().get("agentId");
            if (agentId == null) {
                continue;
            }
            if ("AGENT_FAILED".equals(event.type())) {
                String err = event.attributes().get("error");
                if (err != null) {
                    parseErrors.put(agentId, err);
                }
            }
            if ("AGENT_SUCCEEDED".equals(event.type())) {
                String structured = event.attributes().get("structured");
                if (structured != null) {
                    parsedResponses.put(agentId, structured);
                }
            }
        }
        List<LlmCallView> views = new ArrayList<>();
        for (LlmCallRecord record : records) {
            String parseError = parseErrors.getOrDefault(record.agentId(), record.parseError());
            String parsed = parsedResponses.getOrDefault(record.agentId(), record.parsedResponse());
            views.add(
                    new LlmCallView(
                            record.id(),
                            record.stageId(),
                            record.agentId(),
                            record.provider(),
                            record.model(),
                            record.prompt(),
                            record.rawResponse(),
                            parsed,
                            parseError,
                            record.startedAt(),
                            record.finishedAt(),
                            record.durationMs(),
                            record.metadata()));
        }
        return views;
    }

    static List<String> extractWarnings(Run run, List<RuntimeEvent> events) {
        List<String> warnings = new ArrayList<>();
        Object meta = run.metadata().get("warnings");
        if (meta instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    warnings.add(s);
                }
            }
        }
        for (RuntimeEvent event : events) {
            if ("WARNING_RECORDED".equals(event.type())) {
                String msg = event.attributes().get("message");
                if (msg != null && !msg.isBlank()) {
                    warnings.add(msg);
                }
            }
        }
        return List.copyOf(warnings);
    }

    static String extractTerminalError(Run run, List<RuntimeEvent> events) {
        if (run.status() == RunStatus.CANCELLED) {
            return null;
        }
        for (int i = events.size() - 1; i >= 0; i--) {
            RuntimeEvent event = events.get(i);
            if ("RUN_FAILED".equals(event.type())) {
                String err = event.attributes().get("error");
                if (err != null && !err.isBlank()) {
                    return err;
                }
            }
        }
        for (int i = events.size() - 1; i >= 0; i--) {
            RuntimeEvent event = events.get(i);
            if ("STAGE_FAILED".equals(event.type())) {
                String err = event.attributes().get("error");
                if (err != null && !err.isBlank()) {
                    return err;
                }
            }
        }
        return null;
    }
}
