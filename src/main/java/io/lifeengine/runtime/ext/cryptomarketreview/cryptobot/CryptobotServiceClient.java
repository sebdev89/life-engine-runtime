package io.lifeengine.runtime.ext.cryptomarketreview.cryptobot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;

/**
 * Typed gateway for {@code /api/cryptobot/*}. Every method returns either an {@link ObjectNode}
 * (for single resources) or an {@link ArrayNode} (for collections) so callers get structured
 * JSON they can fold into tool outputs directly. All upstream errors are normalized into
 * {@link CryptobotCallException} so tools can derive consistent {@code TOOL_FAILED} attributes.
 */
@Component
public class CryptobotServiceClient {

    private final WebClient webClient;
    private final CryptobotProperties properties;
    private final ObjectMapper objectMapper;

    public CryptobotServiceClient(
            @Qualifier("cryptobotWebClient") WebClient webClient,
            CryptobotProperties properties,
            ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Mono<ObjectNode> getSnapshot(String symbol) {
        String sym = normalize(symbol);
        return getObject("/api/cryptobot/snapshots/" + sym, b -> b);
    }

    public Mono<ArrayNode> getWatchlist() {
        return getArray("/api/cryptobot/watchlist", b -> b);
    }

    public Mono<ArrayNode> getZones(String symbol) {
        String sym = normalize(symbol);
        return getArray("/api/cryptobot/zones", b -> b.queryParam("symbol", sym));
    }

    public Mono<ArrayNode> getObservations(String symbol, int limit) {
        String sym = normalize(symbol);
        return getArray("/api/cryptobot/observations", b -> b.queryParam("symbol", sym).queryParam("limit", limit));
    }

    public Mono<ArrayNode> getJournal(String symbol, int limit) {
        String sym = normalize(symbol);
        return getArray("/api/cryptobot/journal", b -> b.queryParam("symbol", sym).queryParam("limit", limit));
    }

    public Mono<ArrayNode> getIndicators(String symbol, int limit) {
        String sym = normalize(symbol);
        return getArray("/api/cryptobot/indicators", b -> b.queryParam("symbol", sym).queryParam("limit", limit));
    }

    private Mono<ObjectNode> getObject(String path, java.util.function.Function<UriBuilder, UriBuilder> uriCustomizer) {
        return doGet(path, uriCustomizer)
                .map(node -> {
                    if (node instanceof ObjectNode obj) {
                        return obj;
                    }
                    return objectMapper.createObjectNode();
                });
    }

    private Mono<ArrayNode> getArray(String path, java.util.function.Function<UriBuilder, UriBuilder> uriCustomizer) {
        return doGet(path, uriCustomizer)
                .map(node -> {
                    if (node instanceof ArrayNode arr) {
                        return arr;
                    }
                    return objectMapper.createArrayNode();
                });
    }

    private Mono<JsonNode> doGet(String path, java.util.function.Function<UriBuilder, UriBuilder> uriCustomizer) {
        String endpoint = properties.baseUrl() + path;
        return webClient.get()
                .uri(uri -> uriCustomizer.apply(uri.path(path)).build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(properties.timeout())
                .onErrorMap(WebClientResponseException.class, ex ->
                        new CryptobotCallException(
                                "cryptobot-service responded HTTP " + ex.getStatusCode().value() + " for GET " + path,
                                ex.getStatusCode().value(),
                                endpoint,
                                ex.getResponseBodyAsString(),
                                ex))
                .onErrorMap(ex -> !(ex instanceof CryptobotCallException),
                        ex -> new CryptobotCallException(
                                "cryptobot-service call failed for GET " + path + ": " + ex.getMessage(),
                                null, endpoint, null, ex));
    }

    private static String normalize(String symbol) {
        if (symbol == null) {
            return "";
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
