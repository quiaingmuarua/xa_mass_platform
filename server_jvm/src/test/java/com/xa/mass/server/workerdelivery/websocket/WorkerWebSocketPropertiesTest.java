package com.xa.mass.server.workerdelivery.websocket;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkerWebSocketPropertiesTest {

    @Test
    void enabledAdapterRequiresARealEndpointIdentity() {
        assertThatThrownBy(() -> properties(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("system-polling"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allBoundsMustBePositive() {
        assertThatThrownBy(() -> new WorkerWebSocketProperties(
                true,
                "adapter-1",
                Duration.ZERO,
                100,
                100,
                1000,
                Duration.ofSeconds(5)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static WorkerWebSocketProperties properties(
            String endpointManagerId
    ) {
        return new WorkerWebSocketProperties(
                true,
                endpointManagerId,
                Duration.ofMillis(100),
                100,
                100,
                1000,
                Duration.ofSeconds(5)
        );
    }
}
