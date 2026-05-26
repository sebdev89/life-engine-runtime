package io.lifeengine.runtime.api;

import io.lifeengine.runtime.domain.Run;
import io.lifeengine.runtime.domain.RunStatus;
import io.lifeengine.runtime.domain.RuntimeEvent;
import io.lifeengine.runtime.llm.LlmCallRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class RunDetailAssemblerTest {

    @Test
    void assemble_cancelledRun_hasNoTerminalError() {
        Run run =
                new Run(
                        UUID.randomUUID(),
                        RunStatus.CANCELLED,
                        "demo.llm.workflow",
                        "corr-1",
                        Instant.now(),
                        Instant.now(),
                        Instant.now(),
                        Instant.now(),
                        Map.of());
        RunDetailView view =
                RunDetailAssembler.assemble(
                        run,
                        List.of(),
                        List.of(),
                        List.of(
                                RuntimeEvent.of(
                                        run.id(),
                                        "RUN_FAILED",
                                        Map.of("error", "should ignore"),
                                        false)));
        Assertions.assertThat(view.terminalError()).isNull();
        Assertions.assertThat(view.run().warnings()).isEmpty();
    }

    @Test
    void mapLlmCalls_enrichesParseErrorFromAgentFailed() {
        UUID runId = UUID.randomUUID();
        RuntimeEvent agentFailed =
                RuntimeEvent.of(
                        runId,
                        "AGENT_FAILED",
                        Map.of(
                                "workflowId",
                                "wf",
                                "correlationId",
                                "c",
                                "agentId",
                                "summarizer-agent",
                                "error",
                                "summarizer-agent: invalid JSON"),
                        false);
        LlmCallRecord record =
                new LlmCallRecord(
                        UUID.randomUUID(),
                        "stage-1",
                        "summarizer-agent",
                        "openai-compatible",
                        "m",
                        "prompt",
                        "raw",
                        null,
                        null,
                        Instant.now(),
                        Instant.now(),
                        10,
                        Map.of());
        List<LlmCallView> views = RunDetailAssembler.mapLlmCalls(List.of(record), List.of(agentFailed));
        Assertions.assertThat(views.getFirst().parseError()).contains("invalid JSON");
    }
}
