package io.lifeengine.runtime.ext.devknowledgeanswer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DevKnowledgeAnswerIoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void readInput_acceptsQuestionAndKnowledgeContext() throws Exception {
        String raw =
                """
                {
                  "question": "¿Dónde se valida el JWT?",
                  "knowledgeContext": {
                    "retrievedChunks": [
                      {
                        "documentId": "doc-1",
                        "chunkId": "chunk-1",
                        "title": "JWT validation",
                        "content": "RuntimeJwtAuthenticationWebFilter validates JWT.",
                        "score": 0.91
                      }
                    ]
                  }
                }
                """;

        DevKnowledgeAnswerIo.Input input = DevKnowledgeAnswerIo.readInput(MAPPER, raw);

        assertThat(input.question()).isEqualTo("¿Dónde se valida el JWT?");
        assertThat(input.knowledgeContext().retrievedChunks()).hasSize(1);
        assertThat(input.knowledgeContext().retrievedChunks().get(0).chunkId()).isEqualTo("chunk-1");
    }

    @Test
    void readInput_rejectsMissingQuestion() {
        String raw = "{\"knowledgeContext\":{\"retrievedChunks\":[]}}";
        assertThatThrownBy(() -> DevKnowledgeAnswerIo.readInput(MAPPER, raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question");
    }

    @Test
    void renderKnowledgeBase_formatsChunks() {
        String rendered =
                DevKnowledgeAnswerIo.renderKnowledgeBase(
                        java.util.List.of(
                                new DevKnowledgeAnswerIo.RetrievedChunk(
                                        "doc-1",
                                        "chunk-1",
                                        "JWT validation",
                                        "Filter validates JWT.",
                                        0.9)));

        assertThat(rendered).contains("JWT validation");
        assertThat(rendered).contains("Filter validates JWT.");
        assertThat(rendered).contains("chunkId=chunk-1");
    }

    @Test
    void renderKnowledgeBase_returnsEmptyWhenNoChunks() {
        assertThat(DevKnowledgeAnswerIo.renderKnowledgeBase(java.util.List.of())).isEmpty();
    }
}
