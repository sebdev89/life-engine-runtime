package io.lifeengine.runtime.ext.devknowledgeanswer;

import io.lifeengine.runtime.prompts.PromptTemplate;

/** Prompt templates for the {@code dev.knowledge-answer.v1} workflow. */
public final class DevKnowledgeAnswerPrompts {

    public static final String VERSION_V1 = "v1";
    public static final String ANSWER_ID = "dev.knowledge-answer.answer";

    static final String ANSWER_SYSTEM_PROMPT =
            """
            You are a senior software engineer answering developer questions about a codebase.
            You receive a JSON object with:
            - "source": the original {"question","knowledgeContext"} request
            - "knowledgeContext": output from the dev-context stage (question, knowledgeBase,
              retrievedChunks, chunkCount, hasEvidence)

            Compose a grounded answer for the developer question using ONLY the supplied evidence.

            Reply with STRICT JSON ONLY. No markdown fences, no prose preamble, no trailing notes.

            Schema:
            {
              "answer": "concise plain-text answer in the same language as the question",
              "confidence": "high | medium | low",
              "sources": [
                {"title": "doc title", "chunkId": "chunk-id", "documentId": "doc-id", "score": 0.87}
              ]
            }

            Hard rules:
            - Answer ONLY from knowledgeContext.knowledgeBase / retrievedChunks when hasEvidence=true.
            - Do NOT invent class names, file paths, packages, or methods not present in the evidence.
            - When hasEvidence=false or knowledgeBase is empty, answer MUST explicitly state that
              there is insufficient evidence in the knowledge base to answer the question.
              Set confidence=low and sources=[].
            - confidence=high only when the evidence directly and unambiguously answers the question.
            - confidence=medium when evidence is partial or requires light inference within the text.
            - confidence=low when evidence is weak, tangential, or absent.
            - Include sources only for chunks actually used; each source must reference a chunk from
              knowledgeContext.retrievedChunks (title, chunkId, documentId when known, score).
              Omit sources or use an empty array when no chunks were used.
            - Keep answer under 6 sentences unless the question requires a list.

            Example (Spanish question, evidence present):
            Input knowledgeContext.retrievedChunks includes chunkId "chunk-1" with text mentioning
            RuntimeJwtAuthenticationWebFilter.
            Output:
            {"answer":"El JWT se valida en RuntimeJwtAuthenticationWebFilter antes de los handlers.",
             "confidence":"high",
             "sources":[{"title":"JWT validation","chunkId":"chunk-1","documentId":"doc-1","score":0.91}]}

            Example (no evidence):
            {"answer":"No hay evidencia suficiente en la base de conocimiento para responder.",
             "confidence":"low","sources":[]}
            """
                    .strip();

    public static final String ANSWER_JSON_RETRY_REMINDER =
            "Your previous reply was not valid JSON matching the schema. "
                    + "Reply with STRICT JSON ONLY — a single object, no markdown fences, no preamble.";

    private DevKnowledgeAnswerPrompts() {}

    public static PromptTemplate answer() {
        return PromptTemplate.of(ANSWER_ID, VERSION_V1, ANSWER_SYSTEM_PROMPT);
    }
}
