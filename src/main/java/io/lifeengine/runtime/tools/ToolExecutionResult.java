package io.lifeengine.runtime.tools;

public record ToolExecutionResult(String toolId, boolean success, String output, String error) {

    public static ToolExecutionResult ok(String toolId, String output) {
        return new ToolExecutionResult(toolId, true, output, null);
    }

    public static ToolExecutionResult failed(String toolId, String error) {
        return new ToolExecutionResult(toolId, false, null, error);
    }
}
