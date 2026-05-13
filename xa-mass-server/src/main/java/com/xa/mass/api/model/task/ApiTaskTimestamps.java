package com.xa.mass.api.model.task;

public record ApiTaskTimestamps(
        String createdAt,
        String updatedAt,
        String startedAt,
        String endedAt
) {
}
