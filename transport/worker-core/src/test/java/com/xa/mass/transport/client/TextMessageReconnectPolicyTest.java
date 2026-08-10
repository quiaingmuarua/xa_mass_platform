package com.xa.mass.transport.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class TextMessageReconnectPolicyTest {

    @Test
    void defaultsBoundOneEndpointRun() {
        TextMessageReconnectPolicy policy =
                TextMessageReconnectPolicy.defaults();

        assertEquals(20, policy.maxUnstableAttempts());
        assertEquals(
                Duration.ofMillis(500),
                policy.reconnectInterval()
        );
        assertEquals(
                Duration.ofSeconds(10),
                policy.stableConnectionDuration()
        );
    }
}
