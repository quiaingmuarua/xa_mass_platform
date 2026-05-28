package com.xa.mass.client.task;

public record TaskTimestamps(
        String createdAt,
        String updatedAt,
        String startedAt,
        String endedAt
) {
}
