package io.lifeengine.runtime.api;

import io.lifeengine.runtime.app.RuntimeApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class RuntimeRegistryWebFluxTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void runtimeHealth_returnsComponentStatus() {
        webTestClient
                .get()
                .uri("/api/runtime/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP")
                .jsonPath("$.component")
                .isEqualTo("life-engine-runtime");
    }

    @Test
    void listWorkflows_includesDemoWorkflows() {
        webTestClient
                .get()
                .uri("/api/runtime/workflows")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[?(@.workflowId=='demo.llm.workflow')]")
                .exists();
    }

    @Test
    void listAgents_includesSummarizer() {
        webTestClient
                .get()
                .uri("/api/runtime/agents")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[?(@.agentId=='summarizer-agent')]")
                .exists();
    }

    @Test
    void listTools_includesDemoEcho() {
        webTestClient
                .get()
                .uri("/api/runtime/tools")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[?(@.toolId=='demo.echo')]")
                .exists();
    }
}
