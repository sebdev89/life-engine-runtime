package io.lifeengine.runtime.domain;

import java.time.Instant;
import java.util.Map;

/** Persisted stage summary for a run (agent or tool). */
public record AgentStageRecord(
        String stageId,
        String stageType,
        String name,
        String status,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        String input,
        String output,
        String error,
        Map<String, String> metadata) {

    public AgentStageRecord {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AgentStageRecord agent(
            String stageId,
            String agentId,
            String status,
            Instant startedAt,
            Instant finishedAt,
            String input,
            String output,
            String error,
            Map<String, String> metadata) {
        Long durationMs = duration(startedAt, finishedAt);
        return new AgentStageRecord(
                stageId,
                "AGENT",
                agentId,
                status,
                startedAt,
                finishedAt,
                durationMs,
                input,
                output,
                error,
                metadata);
    }

    public static AgentStageRecord tool(
            String stageId,
            String toolId,
            String status,
            Instant startedAt,
            Instant finishedAt,
            String input,
            String output,
            String error,
            Map<String, String> metadata) {
        Long durationMs = duration(startedAt, finishedAt);
        return new AgentStageRecord(
                stageId,
                "TOOL",
                toolId,
                status,
                startedAt,
                finishedAt,
                durationMs,
                input,
                output,
                error,
                metadata);
    }

    private static Long duration(Instant startedAt, Instant finishedAt) {
        if (startedAt == null || finishedAt == null) {
            return null;
        }
        return finishedAt.toEpochMilli() - startedAt.toEpochMilli();
    }
}
