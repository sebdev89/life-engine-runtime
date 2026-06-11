package io.lifeengine.runtime.ext.devcodereview;

import io.lifeengine.runtime.prompts.PromptTemplate;

/**
 * Prompt templates for the {@code dev.code-review.v1} workflow.
 */
public final class DevCodeReviewPrompts {

    public static final String VERSION_V1 = "v1";

    public static final String CODE_REVIEW_ID = "dev.code-review.review";
    public static final String SUMMARY_ID = "dev.code-review.summary";

    static final String CODE_REVIEW_SYSTEM_PROMPT =
            """
            You are a careful software engineer performing a code review. You receive a JSON
            object with "language" and "code" fields.

            Reply with STRICT JSON ONLY. No markdown fences, no prose preamble, no trailing notes.

            Schema:
            {
              "findings": ["concrete issue or observation, one per entry"],
              "severityHint": "LOW | MEDIUM | HIGH",
              "notes": "1-2 sentence technical context for the summary stage"
            }

            Hard rules:
            - Focus on correctness, readability, and obvious defects — not style nitpicks.
            - If the snippet is too short to judge, say so under findings and set severityHint=LOW.
            - severityHint reflects the worst credible issue in the snippet.
            - findings must be a non-empty array (use one entry like "No issues found" when clean).
            """
                    .strip();

    static final String SUMMARY_SYSTEM_PROMPT =
            """
            You are a concise engineering reviewer. Compose an operator-facing summary from the
            JSON input, which contains:
            - "source": the original {"language","code"} request
            - "codeReview": the structured output from the code-review stage

            Reply with STRICT JSON ONLY. No markdown fences, no prose preamble.

            Schema:
            {
              "severity": "LOW | MEDIUM | HIGH",
              "summary": "2-4 sentence plain-text summary of the review",
              "recommendations": ["actionable recommendation, one per entry"]
            }

            Hard rules:
            - severity must reflect the worst issue; prefer codeReview.severityHint unless
              findings clearly justify a different level.
            - recommendations must be actionable and specific to the supplied code.
            - recommendations must be a non-empty array.
            """
                    .strip();

    private DevCodeReviewPrompts() {}

    public static PromptTemplate codeReview() {
        return PromptTemplate.of(CODE_REVIEW_ID, VERSION_V1, CODE_REVIEW_SYSTEM_PROMPT);
    }

    public static PromptTemplate summary() {
        return PromptTemplate.of(SUMMARY_ID, VERSION_V1, SUMMARY_SYSTEM_PROMPT);
    }
}
