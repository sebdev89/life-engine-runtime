package io.lifeengine.runtime.ext.agenttesting.stages;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentTestingDeterministicEvaluatorTest {

    private final AgentTestingDeterministicEvaluator evaluator =
            new AgentTestingDeterministicEvaluator(new ObjectMapper().findAndRegisterModules());

    @Test
    void evaluateJson_handoffCorrectnessPass() throws Exception {
        String input =
                """
                {
                  "scenarioId": "legal-normal-faq",
                  "transcript": [
                    {"turn": 1, "handoffRequired": false, "leadCaptured": true, "lead": {}, "intent": "faq", "botResponse": "ok"}
                  ],
                  "assertions": [
                    {"dimension": "handoff_correctness", "expectedHandoff": false}
                  ],
                  "operatorOutcome": {"delivered": false}
                }
                """;
        String output = evaluator.evaluateJson(input);
        Assertions.assertThat(output).contains("\"verdict\":\"PASS\"");
        Assertions.assertThat(output).contains("handoff_correctness");
    }
}
