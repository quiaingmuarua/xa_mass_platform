package com.xa.mass.api.model.task;

public record ApiTaskCounters(
        int targetCount,
        int eligibleCount,
        int successCount,
        int nonSuccessCount,
        int minRequiredWorkerCount,
        int peakAssignedWorkerCount
) {
}
