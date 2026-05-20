package io.lifeengine.runtime.llm;

import java.util.Map;

public record LlmResponse(String content, Map<String, Object> usage) {}
