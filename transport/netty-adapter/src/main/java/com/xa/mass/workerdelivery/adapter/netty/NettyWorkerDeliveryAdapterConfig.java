package com.xa.mass.workerdelivery.adapter.netty;

import java.time.Duration;
import java.util.Objects;

/** Complete construction configuration for one Netty Adapter instance. */
public record NettyWorkerDeliveryAdapterConfig(
        Type type,
        String listenHost,
        int listenPort,
        Duration commandBackoff,
        int commandConsumeLimit,
        int commandRetryCapacity,
        Duration reportBackoff,
        int reportQueueCapacity,
        Duration reconnectVerificationRetention,
        long maximumDisconnectedWorkers,
        long maximumEncodedPropertiesBytes,
        Duration sendTimeLimit,
        Duration shutdownTimeout
) {

    public NettyWorkerDeliveryAdapterConfig {
        Objects.requireNonNull(type, "type");
        if (listenHost == null || listenHost.isBlank()) {
            throw new IllegalArgumentException(
                    "listenHost must be non-blank"
            );
        }
        if (listenPort < 1 || listenPort > 65_535) {
            throw new IllegalArgumentException(
                    "listenPort must be between 1 and 65535"
            );
        }
        commandBackoff = requirePositiveMillis(
                commandBackoff,
                "commandBackoff"
        );
        requireQueueCapacity(
                commandRetryCapacity,
                1,
                "commandRetryCapacity"
        );
        if (commandConsumeLimit <= 0
                || commandRetryCapacity < commandConsumeLimit) {
            throw new IllegalArgumentException(
                    "commandConsumeLimit must be between 1 and "
                            + "commandRetryCapacity"
            );
        }
        reportBackoff = requirePositiveMillis(
                reportBackoff,
                "reportBackoff"
        );
        requireQueueCapacity(
                reportQueueCapacity,
                2,
                "reportQueueCapacity"
        );
        reconnectVerificationRetention = requirePositive(
                reconnectVerificationRetention,
                "reconnectVerificationRetention"
        );
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
        if (maximumEncodedPropertiesBytes <= 0) {
            throw new IllegalArgumentException(
                    "maximumEncodedPropertiesBytes must be positive"
            );
        }
        sendTimeLimit = requirePositiveMillis(
                sendTimeLimit,
                "sendTimeLimit"
        );
        shutdownTimeout = requirePositiveMillis(
                shutdownTimeout,
                "shutdownTimeout"
        );
    }

    private static Duration requirePositiveMillis(
            Duration value,
            String name
    ) {
        Duration required = requirePositive(value, name);
        if (required.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return required;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static void requireQueueCapacity(
            int value,
            int minimum,
            String name
    ) {
        if (value < minimum) {
            throw new IllegalArgumentException(
                    name + " must be at least " + minimum
            );
        }
        if (2L * value - 1L > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is too large");
        }
    }

    public enum Type {
        WEBSOCKET,
        SOCKET
    }
}
