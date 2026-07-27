package com.xa.mass.server.workerdelivery.adapter;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ServerWorkerDeliveryAdapterPropertiesTest {

    @Test
    void enabledAdapterRequiresDedicatedEndpointManager() {
        assertThatThrownBy(() -> properties(true, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpointManagerId");
        assertThatThrownBy(() -> properties(true, "system-polling"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system-polling");
    }

    @Test
    void rejectsInvalidGatewayAndBounds() {
        assertThatThrownBy(() -> new ServerWorkerDeliveryAdapterProperties(
                true,
                "websocket-adapter",
                URI.create("file:///tmp/gateway"),
                Duration.ofSeconds(1),
                Duration.ofMillis(100),
                100,
                100,
                1000,
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP");

        assertThatThrownBy(() -> new ServerWorkerDeliveryAdapterProperties(
                true,
                "websocket-adapter",
                URI.create("http://127.0.0.1:18082"),
                Duration.ofSeconds(1),
                Duration.ZERO,
                100,
                100,
                1000,
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pumpInterval");
    }

    private static ServerWorkerDeliveryAdapterProperties properties(
            boolean enabled,
            String endpointManagerId
    ) {
        return new ServerWorkerDeliveryAdapterProperties(
                enabled,
                endpointManagerId,
                URI.create("http://127.0.0.1:18082"),
                Duration.ofSeconds(1),
                Duration.ofMillis(100),
                100,
                100,
                1000,
                Duration.ofSeconds(1)
        );
    }
}
