package com.xa.mass.runtime.api;

public record TaskResultRetentionPolicy(long keepLatestRows) {

    public static TaskResultRetentionPolicy retainAll() {
        return new TaskResultRetentionPolicy(Long.MAX_VALUE);
    }

    public TaskResultRetentionPolicy {
        if (keepLatestRows <= 0) {
            throw new IllegalArgumentException("keepLatestRows must be greater than 0");
        }
    }
}
