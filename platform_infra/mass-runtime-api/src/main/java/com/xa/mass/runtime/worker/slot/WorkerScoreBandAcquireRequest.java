package com.xa.mass.runtime.worker.slot;

import java.util.ArrayList;
import java.util.List;

public record WorkerScoreBandAcquireRequest(
        List<String> homeBucketIds,
        String targetWorkerId,
        int maxCount,
        long nowMillis
) {

    public WorkerScoreBandAcquireRequest {
        homeBucketIds = normalizeHomeBucketIds(homeBucketIds);
        targetWorkerId = normalizeNullable(targetWorkerId);
        maxCount = Math.max(0, maxCount);
    }

    public static WorkerScoreBandAcquireRequest inHomeBucket(String homeBucketId,
                                                             int maxCount,
                                                             long nowMillis) {
        return new WorkerScoreBandAcquireRequest(List.of(homeBucketId), null, maxCount, nowMillis);
    }

    public static WorkerScoreBandAcquireRequest targetInHomeBuckets(List<String> homeBucketIds,
                                                                    String targetWorkerId,
                                                                    long nowMillis) {
        return new WorkerScoreBandAcquireRequest(homeBucketIds, targetWorkerId, 1, nowMillis);
    }

    private static List<String> normalizeHomeBucketIds(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        ArrayList<String> normalized = new ArrayList<>();
        for (String value : values) {
            String token = normalizeNullable(value);
            if (token != null && !normalized.contains(token)) {
                normalized.add(token);
            }
        }
        return List.copyOf(normalized);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
