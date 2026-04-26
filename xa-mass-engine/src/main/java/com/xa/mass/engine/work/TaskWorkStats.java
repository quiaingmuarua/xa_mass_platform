package com.xa.mass.engine.work;

public record TaskWorkStats(long readyCount,
                            long inflightCount,
                            long delayedCount,
                            long successCount,
                            long failedCount,
                            long expiredCount) {
    public static final TaskWorkStats EMPTY = new TaskWorkStats(0, 0, 0, 0, 0, 0);
}
