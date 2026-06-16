package io.lifeengine.runtime.tools.search;

import java.util.List;
import reactor.core.publisher.Mono;

class MockSearchProvider implements SearchProvider {

    static final List<SearchResult> FIXED_RESULTS = List.of(
            new SearchResult(
                    "Life Engine Platform Docs",
                    "https://docs.lifeengine.io/overview",
                    "Life Engine is a multi-vertical AI agent SaaS platform built on Spring Boot 3.4 and WebFlux."),
            new SearchResult(
                    "Spring Boot 3 Reference Guide",
                    "https://docs.spring.io/spring-boot/reference/",
                    "Spring Boot makes it easy to create production-grade Spring-based applications."),
            new SearchResult(
                    "Project Reactor Documentation",
                    "https://projectreactor.io/docs/core/release/reference/",
                    "Reactor is a reactive library for building non-blocking applications on the JVM."));

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Mono<List<SearchResult>> search(String query, int maxResults) {
        int limit = Math.min(maxResults, FIXED_RESULTS.size());
        return Mono.just(FIXED_RESULTS.subList(0, limit));
    }
}
