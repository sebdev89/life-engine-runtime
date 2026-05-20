package io.lifeengine.runtime.llm;

/** OpenAI-compatible chat message. */
public record LlmMessage(String role, String content) {}
