package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import java.time.Duration;
import java.util.Objects;

/** Immutable scheduling metadata for one Adapter process. */
public record ScheduledAdapterProcess(
        String processId,
        Duration initialDelay,
        Duration interval,
        QuiescePhase quiescePhase,
        AdapterProcess process
) {

    public ScheduledAdapterProcess {
        if (processId == null || processId.isBlank()) {
            throw new IllegalArgumentException(
                    "processId must be non-blank"
            );
        }
        initialDelay = requireNonNegative(initialDelay, "initialDelay");
        interval = requirePositive(interval, "interval");
        quiescePhase = Objects.requireNonNull(
                quiescePhase,
                "quiescePhase"
        );
        process = Objects.requireNonNull(process, "process");
    }

    private static Duration requireNonNegative(
            Duration value,
            String name
    ) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(
                    name + " must not be negative"
            );
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
