package com.xa.mass.workerdelivery.adapter.websocket;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
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
                URI.create("http://127.0.0.1:18082"),
                Duration.ofSeconds(5),
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
                URI.create("http://127.0.0.1:18082"),
                Duration.ofSeconds(5),
                Duration.ofMillis(100),
                100,
                100,
                1000,
                Duration.ofSeconds(5)
        );
    }
}
