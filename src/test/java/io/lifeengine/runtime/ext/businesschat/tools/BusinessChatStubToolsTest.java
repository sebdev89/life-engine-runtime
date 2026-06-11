package io.lifeengine.runtime.ext.businesschat.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.runtime.core.InMemoryRunStore;
import io.lifeengine.runtime.events.RunEventPublisher;
import io.lifeengine.runtime.tools.ToolExecutionRequest;
import io.lifeengine.runtime.tools.ToolExecutionResult;
import io.lifeengine.runtime.workflow.WorkflowRunContext;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class BusinessChatStubToolsTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void getBusinessInfo_stubReturnsOutput() {
        GetBusinessInfoStubTool tool = new GetBusinessInfoStubTool(mapper);
        WorkflowRunContext ctx = testContext();

        StepVerifier.create(
                        tool.execute(
                                new ToolExecutionRequest(
                                        ctx.runId(),
                                        tool.toolId(),
                                        "{\"botId\":\"demo\",\"businessName\":\"Acme\"}",
                                        java.util.Map.of()),
                                ctx))
                .assertNext(
                        result -> {
                            Assertions.assertThat(result.success()).isTrue();
                            Assertions.assertThat(result.output()).contains("stub");
                            Assertions.assertThat(result.output()).contains("Acme");
                        })
                .verifyComplete();
    }

    @Test
    void createLead_missingLead_failsWithoutCrash() {
        CreateLeadStubTool tool = new CreateLeadStubTool(mapper);
        WorkflowRunContext ctx = testContext();

        ToolExecutionResult result =
                tool.execute(
                                new ToolExecutionRequest(ctx.runId(), tool.toolId(), "{}", java.util.Map.of()),
                                ctx)
                        .block();
        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.success()).isFalse();
        Assertions.assertThat(result.error()).isEqualTo("missing_lead_data");
    }

    @Test
    void requestHumanHandoff_stubReturnsPendingStatus() {
        RequestHumanHandoffStubTool tool = new RequestHumanHandoffStubTool(mapper);
        WorkflowRunContext ctx = testContext();

        StepVerifier.create(
                        tool.execute(
                                new ToolExecutionRequest(
                                        ctx.runId(),
                                        tool.toolId(),
                                        "{\"conversationId\":\"conv-1\",\"reason\":\"pide_humano\"}",
                                        java.util.Map.of()),
                                ctx))
                .assertNext(
                        result -> {
                            Assertions.assertThat(result.success()).isTrue();
                            Assertions.assertThat(result.output()).contains("PENDING");
                        })
                .verifyComplete();
    }

    private static WorkflowRunContext testContext() {
        return new WorkflowRunContext(
                UUID.randomUUID(),
                "business-chat.reply.v1",
                "corr-test",
                "{}",
                new InMemoryRunStore(),
                new RunEventPublisher(),
                new AtomicBoolean(false));
    }
}
