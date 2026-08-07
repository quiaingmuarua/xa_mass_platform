package com.xa.mass.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.transport.client.TextMessageReconnectPolicy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class WorkerRetryPolicyTest {

    @Test
    void defaultsLockBothRetryBudgets() {
        WorkerRetryPolicy policy = WorkerRetryPolicy.defaults();

        assertEquals(10, policy.maxPrepareAttempts());
        assertEquals(Duration.ofSeconds(1),
                policy.prepareRetryInterval());
        assertEquals(20,
                policy.connectionPolicy().maxUnstableAttempts());
        assertEquals(Duration.ofMillis(500),
                policy.connectionPolicy().reconnectInterval());
        assertEquals(Duration.ofSeconds(10),
                policy.connectionPolicy().stableConnectionDuration());
    }

    @Test
    void policiesRejectNonPositiveValues() {
        TextMessageReconnectPolicy connection =
                TextMessageReconnectPolicy.of(
                        1,
                        Duration.ofMillis(1),
                        Duration.ofMillis(1)
                );

        assertThrows(IllegalArgumentException.class,
                () -> WorkerRetryPolicy.of(
                        0,
                        Duration.ofSeconds(1),
                        connection
                ));
        assertThrows(IllegalArgumentException.class,
                () -> WorkerRetryPolicy.of(
                        1,
                        Duration.ZERO,
                        connection
                ));
        assertThrows(IllegalArgumentException.class,
                () -> TextMessageReconnectPolicy.of(
                        0,
                        Duration.ofMillis(1),
                        Duration.ofMillis(1)
                ));
        assertThrows(IllegalArgumentException.class,
                () -> TextMessageReconnectPolicy.of(
                        1,
                        Duration.ZERO,
                        Duration.ofMillis(1)
                ));
        assertThrows(IllegalArgumentException.class,
                () -> TextMessageReconnectPolicy.of(
                        1,
                        Duration.ofMillis(1),
                        Duration.ZERO
                ));
    }
}
