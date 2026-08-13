package com.xa.mass.workerdelivery.adapter.netty;

import java.time.Duration;
import java.util.Objects;

/** Finite public construction configuration for built-in Adapter processes. */
public sealed interface NettyAdapterProcessConfig
        permits NettyAdapterProcessConfig.TaskCommand,
        NettyAdapterProcessConfig.TaskReport {

    record TaskCommand(
            Duration interval,
            int consumeLimit,
            int queueCapacity
    ) implements NettyAdapterProcessConfig {

        public TaskCommand {
            interval = requirePositive(interval, "interval");
            if (consumeLimit <= 0 || queueCapacity < consumeLimit) {
                throw new IllegalArgumentException(
                        "consumeLimit must be between 1 and queueCapacity"
                );
            }
        }
    }

    record TaskReport(
            Duration interval,
            int queueCapacity
    ) implements NettyAdapterProcessConfig {

        public TaskReport {
            interval = requirePositive(interval, "interval");
            if (queueCapacity <= 0) {
                throw new IllegalArgumentException(
                        "queueCapacity must be positive"
                );
            }
        }
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
