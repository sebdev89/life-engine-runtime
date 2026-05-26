package io.lifeengine.runtime.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StrictAgentJsonTest {

    // --- happy path ------------------------------------------------------------------------

    @Test
    void parseSummarizer_validObject() {
        String raw =
                """
                {"incident":"CPU saturation","affectedResource":"node-3","requestedAction":"Review scaling"}
                """;
        StrictAgentJson.SummarizerOutput out = StrictAgentJson.parseSummarizer(raw);
        assertThat(out.incident()).isEqualTo("CPU saturation");
        assertThat(out.affectedResource()).isEqualTo("node-3");
        assertThat(out.requestedAction()).isEqualTo("Review scaling");
    }

    @Test
    void parseClassifier_validObject() {
        String raw = "{\"category\":\"ACTION\",\"reason\":\"Operator must review deploys.\"}";
        StrictAgentJson.ClassifierOutput out = StrictAgentJson.parseClassifier(raw);
        assertThat(out.category()).isEqualTo("ACTION");
        assertThat(out.reason()).isEqualTo("Operator must review deploys.");
    }

    // --- transport-noise tolerance (markdown fences, preamble, trailing prose) ------------
    //
    // These are the real-world Qwen/Llama instruct outputs the strict parser must survive.
    // The JSON grammar is still strict; only the surrounding wrapper is stripped.

    @Nested
    class MarkdownFences {

        @Test
        void fenceWithLanguageTag_accepted() {
            String raw =
                    """
                    ```json
                    {"incident":"x","affectedResource":"y","requestedAction":"z"}
                    ```
                    """;
            StrictAgentJson.SummarizerOutput out = StrictAgentJson.parseSummarizer(raw);
            assertThat(out.incident()).isEqualTo("x");
            assertThat(out.affectedResource()).isEqualTo("y");
            assertThat(out.requestedAction()).isEqualTo("z");
        }

        @Test
        void fenceWithoutLanguageTag_accepted() {
            String raw =
                    """
                    ```
                    {"incident":"a","affectedResource":"b","requestedAction":"c"}
                    ```
                    """;
            StrictAgentJson.SummarizerOutput out = StrictAgentJson.parseSummarizer(raw);
            assertThat(out.incident()).isEqualTo("a");
        }

        @Test
        void fenceWithUppercaseLanguageTag_accepted() {
            String raw =
                    """
                    ```JSON
                    {"incident":"u","affectedResource":"v","requestedAction":"w"}
                    ```
                    """;
            StrictAgentJson.SummarizerOutput out = StrictAgentJson.parseSummarizer(raw);
            assertThat(out.incident()).isEqualTo("u");
        }

        @Test
        void fenceWithBackticksInsideStringLiteral_accepted() {
            // Defends the fence stripper: backticks embedded in a string literal must not
            // confuse extraction. We rely on lastIndexOf("```") for the closing fence and on
            // balanced-bracket walking for the JSON payload.
            String raw =
                    """
                    ```json
                    {"incident":"see logs `journalctl -u svc`","affectedResource":"node-1","requestedAction":"restart"}
                    ```
                    """;
            StrictAgentJson.SummarizerOutput out = StrictAgentJson.parseSummarizer(raw);
            assertThat(out.incident()).contains("journalctl");
            assertThat(out.affectedResource()).isEqualTo("node-1");
        }
    }

    @Nested
    class ConversationalPreambleAndTrailer {

        @Test
        void preambleBeforeJson_accepted() {
            String raw =
                    "Sure, here is the JSON you requested:\n"
                            + "{\"incident\":\"disk full\",\"affectedResource\":\"node-7\",\"requestedAction\":\"prune\"}";
            StrictAgentJson.SummarizerOutput out = StrictAgentJson.parseSummarizer(raw);
            assertThat(out.incident()).isEqualTo("disk full");
            assertThat(out.affectedResource()).isEqualTo("node-7");
        }

        @Test
        void trailingProseAfterJson_accepted() {
            String raw =
                    "{\"incident\":\"oom\",\"affectedResource\":\"pod-x\",\"requestedAction\":\"scale\"}"
                            + "\nLet me know if you need more detail!";
            StrictAgentJson.SummarizerOutput out = StrictAgentJson.parseSummarizer(raw);
            assertThat(out.incident()).isEqualTo("oom");
        }

        @Test
        void preambleAndFenceAndTrailer_accepted() {
            String raw =
                    """
                    Here is the JSON for the incident:
                    ```json
                    {"incident":"i","affectedResource":"r","requestedAction":"a"}
                    ```
                    Hope that helps!
                    """;
            StrictAgentJson.SummarizerOutput out = StrictAgentJson.parseSummarizer(raw);
            assertThat(out.incident()).isEqualTo("i");
            assertThat(out.affectedResource()).isEqualTo("r");
            assertThat(out.requestedAction()).isEqualTo("a");
        }

        @Test
        void nestedObjectInsideTopLevel_extractedCorrectly() {
            // The balanced-bracket walker must respect inner braces and not stop early.
            String raw =
                    "noise {\"incident\":\"x\",\"affectedResource\":\"y\",\"requestedAction\":\"z\",\"meta\":{\"k\":\"v\"}} tail";
            StrictAgentJson.SummarizerOutput out = StrictAgentJson.parseSummarizer(raw);
            assertThat(out.incident()).isEqualTo("x");
        }

        @Test
        void bracesInsideStringLiteralIgnoredByDepthCounter() {
            String raw =
                    "preamble "
                            + "{\"incident\":\"saw } in log\",\"affectedResource\":\"n\",\"requestedAction\":\"r\"}"
                            + " trailer";
            StrictAgentJson.SummarizerOutput out = StrictAgentJson.parseSummarizer(raw);
            assertThat(out.incident()).contains("saw }");
        }
    }

    // --- still-strict rejections (regression guards) --------------------------------------

    @Test
    void parseSummarizer_invalidJson_fails() {
        assertThatThrownBy(() -> StrictAgentJson.parseSummarizer("not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid JSON");
    }

    @Test
    void parseSummarizer_proseWithoutAnyJsonValue_fails() {
        // The wire test exercised in LlmWorkflowWebFluxTest — must keep failing cleanly.
        assertThatThrownBy(
                        () ->
                                StrictAgentJson.parseSummarizer(
                                        "Here is a plain text summary, not JSON."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid JSON");
    }

    @Test
    void parseSummarizer_empty_fails() {
        assertThatThrownBy(() -> StrictAgentJson.parseSummarizer(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty LLM response");
    }

    @Test
    void parseSummarizer_arrayRoot_fails() {
        assertThatThrownBy(
                        () ->
                                StrictAgentJson.parseSummarizer(
                                        "[{\"incident\":\"x\",\"affectedResource\":\"y\",\"requestedAction\":\"z\"}]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseSummarizer_missingField_fails() {
        // Strictness preserved: the grammar parses, but required fields are still enforced.
        assertThatThrownBy(
                        () ->
                                StrictAgentJson.parseSummarizer(
                                        "{\"incident\":\"x\",\"affectedResource\":\"y\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing or empty field: requestedAction");
    }

    @Test
    void parseSummarizer_trailingComma_stillRejected() {
        // We tolerate transport-layer wrappers, NOT JSON-grammar relaxations.
        assertThatThrownBy(
                        () ->
                                StrictAgentJson.parseSummarizer(
                                        "{\"incident\":\"x\",\"affectedResource\":\"y\",\"requestedAction\":\"z\",}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid JSON");
    }

    @Test
    void parseSummarizer_singleQuotes_stillRejected() {
        assertThatThrownBy(
                        () ->
                                StrictAgentJson.parseSummarizer(
                                        "{'incident':'x','affectedResource':'y','requestedAction':'z'}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid JSON");
    }

    @Test
    void parseClassifier_unknownCategory_fails() {
        String raw = "{\"category\":\"URGENT\",\"reason\":\"x\"}";
        assertThatThrownBy(() -> StrictAgentJson.parseClassifier(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("category must be one of");
    }

    // --- canonicalJson ---------------------------------------------------------------------

    @Test
    void canonicalJson_unwrapsMarkdownFenceBeforeSerializing() {
        String raw =
                """
                ```json
                {"incident":"x","affectedResource":"y","requestedAction":"z"}
                ```
                """;
        String canonical = StrictAgentJson.canonicalJson(raw);
        assertThat(canonical).startsWith("{").endsWith("}").doesNotContain("```");
    }
}
