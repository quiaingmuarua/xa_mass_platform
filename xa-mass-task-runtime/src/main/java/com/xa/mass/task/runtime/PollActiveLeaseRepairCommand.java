package com.xa.mass.task.runtime;

public record PollActiveLeaseRepairCommand(int limit, long nowMillis) {

    public PollActiveLeaseRepairCommand {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        nowMillis = Math.max(0L, nowMillis);
    }
}
