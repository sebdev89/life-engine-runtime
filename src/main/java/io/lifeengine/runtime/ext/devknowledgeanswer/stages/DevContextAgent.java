package io.lifeengine.runtime.ext.devknowledgeanswer.stages;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lifeengine.runtime.agents.AgentExecutionRequest;
import io.lifeengine.runtime.agents.AgentExecutionResult;
import io.lifeengine.runtime.agents.AgentExecutor;
import io.lifeengine.runtime.domain.EventType;
import io.lifeengine.runtime.ext.devknowledgeanswer.DevKnowledgeAnswerIo;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Stage 1 — deterministic context builder. Validates workflow input, renders {@code knowledgeBase}
 * from retrieved chunks, and prepares evidence metadata for the answer stage. No LLM call.
 */
@Component
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.dev-knowledge-answer.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DevContextAgent implements AgentExecutor {

    public static final String AGENT_ID = "dev-context-agent";

    private final ObjectMapper mapper;

    public DevContextAgent(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public Mono<AgentExecutionResult> execute(AgentExecutionRequest request, WorkflowRunContext ctx) {
        if (ctx.isCancelled()) {
            return Mono.error(new IllegalStateException("Run cancelled"));
        }
        ctx.emit(EventType.AGENT_STARTED, Map.of("agentId", AGENT_ID), false);
        try {
            DevKnowledgeAnswerIo.Input parsed = DevKnowledgeAnswerIo.readInput(mapper, request.input());
            List<DevKnowledgeAnswerIo.RetrievedChunk> chunks = parsed.knowledgeContext().retrievedChunks();
            int chunkCount = chunks.size();
            boolean hasEvidence = chunkCount > 0;
            String knowledgeBase = DevKnowledgeAnswerIo.renderKnowledgeBase(chunks);

            ObjectNode out = mapper.createObjectNode();
            out.put("question", parsed.question());
            out.put("knowledgeBase", knowledgeBase);
            out.set("retrievedChunks", mapper.valueToTree(DevKnowledgeAnswerIo.toChunkMaps(chunks)));
            out.put("chunkCount", chunkCount);
            out.put("hasEvidence", hasEvidence);

            String canonical = mapper.writeValueAsString(out);
            ctx.putAgentOutput(AGENT_ID, canonical);

            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("agentId", AGENT_ID);
            attrs.put("chunkCount", Integer.toString(chunkCount));
            attrs.put("hasEvidence", Boolean.toString(hasEvidence));
            attrs.put("structured", WorkflowRunContext.truncate(canonical, 500));
            ctx.emit(EventType.AGENT_SUCCEEDED, attrs, false);

            return Mono.just(AgentExecutionResult.ok(AGENT_ID, canonical));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            ctx.emit(
                    EventType.AGENT_FAILED,
                    Map.of("agentId", AGENT_ID, "error", WorkflowRunContext.truncate(msg, 240)),
                    false);
            return Mono.error(e);
        }
    }
}
