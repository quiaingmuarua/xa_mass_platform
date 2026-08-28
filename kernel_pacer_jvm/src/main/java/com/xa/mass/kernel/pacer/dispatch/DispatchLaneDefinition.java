package com.xa.mass.kernel.pacer.dispatch;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

record DispatchLaneDefinition(
        DispatchLaneId id,
        long intervalNanos,
        DispatchBatchPolicy policy
) {
    DispatchLaneDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(policy, "policy");
        if (intervalNanos < 1) {
            throw new IllegalArgumentException(
                    "Dispatch lane interval must be positive"
            );
        }
    }

    static DispatchLaneDefinition fromMillis(
            DispatchLaneId id,
            long intervalMillis,
            DispatchBatchPolicy policy
    ) {
        if (intervalMillis < 1) {
            throw new IllegalArgumentException(
                    "Dispatch lane interval must be positive"
            );
        }
        return new DispatchLaneDefinition(
                Objects.requireNonNull(id, "id"),
                TimeUnit.MILLISECONDS.toNanos(intervalMillis),
                Objects.requireNonNull(policy, "policy")
        );
    }
}
