package io.lifeengine.runtime.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JwksPublicKeyProviderTest {

    private static final String JWKS_BODY =
            """
            {"keys":[{"kty":"RSA","kid":"test-kid","alg":"RS256","use":"sig",
              "n":"mpXLaOX6CSio3ULUrgB5PRKoNuG-ivUfpyV24Yca9YhQLE9w4qYWV9DXb1aT63aEBVPAkTN1jnD1AUrS_TUjVv6GIgcfF1v2pvEjRPI9_mN1nG_pj4YT7yfhrmATRCLPKPZtRkJy5M9ACzEzKFZJGclwnlThS_3RfssFvdJyJ9vDNyxWuv992CxAn0uk1MsMwFPZU0rK_1qXRKdg8JI6lpOBkGdRirHm2b8NbxjkeXPT2EhUviAV4M4hhIvmOv2dO4cQHsOBTIoeSMQ-mtMXZLehrIIqBD4Eiulye-EYlwcl7HRbwPZqYl8-ELTzW-vzBBkBzOyqk8jta8ohQ01lGQ",
              "e":"AQAB"}]}
            """;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startFakeJwksServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/.well-known/jwks.json",
                exchange -> {
                    byte[] bytes = JWKS_BODY.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json";
    }

    @Test
    void getPublicKey_fetchesAndCachesFromJwksEndpoint() throws Exception {
        String uri = startFakeJwksServer();
        var provider = new JwksPublicKeyProvider(new RuntimeJwksProperties(uri), new ObjectMapper());

        var key = provider.getPublicKey("test-kid");

        assertThat(key).isPresent();
    }

    @Test
    void isConfigured_isFalse_whenUriBlank() {
        var provider = new JwksPublicKeyProvider(new RuntimeJwksProperties(""), new ObjectMapper());

        assertThat(provider.isConfigured()).isFalse();
        assertThat(provider.getPublicKey("any")).isEmpty();
    }
}
