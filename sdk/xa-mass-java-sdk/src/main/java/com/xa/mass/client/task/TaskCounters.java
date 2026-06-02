package com.xa.mass.client.task;

public record TaskCounters(
        int targetCount,
        int eligibleCount,
        int successCount,
        int nonSuccessCount,
        int minRequiredWorkerCount,
        int peakAssignedWorkerCount
) {
}
