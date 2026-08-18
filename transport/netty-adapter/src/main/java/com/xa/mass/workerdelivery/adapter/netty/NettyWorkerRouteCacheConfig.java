package com.xa.mass.workerdelivery.adapter.netty;

import java.time.Duration;
import java.util.Objects;

/** Finite retention policy for one Adapter's reconnect verification evidence. */
public record NettyWorkerRouteCacheConfig(
        Duration reconnectVerificationRetention,
        long maximumDisconnectedWorkers
) {

    public NettyWorkerRouteCacheConfig {
        Objects.requireNonNull(
                reconnectVerificationRetention,
                "reconnectVerificationRetention"
        );
        if (reconnectVerificationRetention.isZero()
                || reconnectVerificationRetention.isNegative()) {
            throw new IllegalArgumentException(
                    "reconnectVerificationRetention must be positive"
            );
        }
        try {
            reconnectVerificationRetention.toNanos();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(
                    "reconnectVerificationRetention is too large",
                    error
            );
        }
        if (maximumDisconnectedWorkers <= 0) {
            throw new IllegalArgumentException(
                    "maximumDisconnectedWorkers must be positive"
            );
        }
    }
}
