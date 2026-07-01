package com.xa.mass.task.runtime;

public record SchedulerDiscoveryCommand(int limit, long nowMillis) {

    public SchedulerDiscoveryCommand {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }
}
