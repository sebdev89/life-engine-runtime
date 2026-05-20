package io.lifeengine.runtime.llm;

import java.util.List;

public record LlmRequest(String model, List<LlmMessage> messages) {}
