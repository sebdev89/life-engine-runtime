package io.lifeengine.runtime.prompts;

/** Raised when no {@link PromptTemplate} is registered for the requested {@code id@version}. */
public class PromptTemplateNotFoundException extends RuntimeException {

    private final String id;
    private final String version;

    public PromptTemplateNotFoundException(String id, String version) {
        super("Unknown prompt template: " + id + "@" + version);
        this.id = id;
        this.version = version;
    }

    public String id() {
        return id;
    }

    public String version() {
        return version;
    }
}
