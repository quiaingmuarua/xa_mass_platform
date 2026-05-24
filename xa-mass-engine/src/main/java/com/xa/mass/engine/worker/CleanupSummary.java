package com.xa.mass.engine.worker;

public record CleanupSummary(
        int scanned,
        int removed,
        int skipped
) {

    public CleanupSummary {
        scanned = Math.max(0, scanned);
        removed = Math.max(0, removed);
        skipped = Math.max(0, skipped);
    }

    public static CleanupSummary empty() {
        return new CleanupSummary(0, 0, 0);
    }
}
