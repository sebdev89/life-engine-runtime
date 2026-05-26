package io.lifeengine.runtime.tools;

public class ToolNotFoundException extends RuntimeException {

    private final String toolId;

    public ToolNotFoundException(String toolId) {
        super("Unknown tool: " + toolId);
        this.toolId = toolId;
    }

    public String toolId() {
        return toolId;
    }
}
