package com.xa.mass.workersimulator.messaging;

/** Captured Host-local bounds. These do not configure Runtime Item retention or delivery. */
public record MessageSettings(long dedupWindowMillis, int maxDedupEntries,
        long receiptWindowMillis, int maxTrackedMessages, int maxRecentSentRecords) {
    public MessageSettings {
        if (dedupWindowMillis <= 0 || receiptWindowMillis <= 0 || maxDedupEntries <= 0
                || maxTrackedMessages <= 0 || maxRecentSentRecords <= 0)
            throw new IllegalArgumentException("Messages windows and capacities must be positive");
        try {
            Math.multiplyExact(dedupWindowMillis, 1_000_000L);
            Math.multiplyExact(receiptWindowMillis, 1_000_000L);
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Messages windows are too large", invalid);
        }
    }
    public static MessageSettings defaults() { return new MessageSettings(600_000, 200_000, 600_000, 20_000, 1_000); }
    long dedupWindowNanos() { return dedupWindowMillis * 1_000_000L; }
    long receiptWindowNanos() { return receiptWindowMillis * 1_000_000L; }
}
