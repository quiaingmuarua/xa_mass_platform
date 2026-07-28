package com.xa.mass.workerdelivery.adapter.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkerDeliveryAdapterConfigTest {

    @Test
    void runtimeConfigRejectsPollingInvalidGatewayAndBounds() {
        assertThatThrownBy(() -> runtime(
                "system-polling",
                URI.create("http://127.0.0.1:18082"),
                Duration.ofMillis(100),
                100
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system-polling");

        assertThatThrownBy(() -> runtime(
                "websocket-1",
                URI.create("file:///tmp/gateway"),
                Duration.ofMillis(100),
                100
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP");

        assertThatThrownBy(() -> runtime(
                "websocket-1",
                URI.create("http://127.0.0.1:18082"),
                Duration.ZERO,
                100
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dispatchInterval");

        assertThatThrownBy(() -> runtime(
                "websocket-1",
                URI.create("http://127.0.0.1:18082"),
                Duration.ofMillis(100),
                0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounds");
    }

    @Test
    void websocketConfigRejectsInvalidSendLimit() {
        assertThatThrownBy(() ->
                new WebSocketWorkerDeliveryAdapterConfig(Duration.ZERO)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sendTimeLimit");
    }

    private static WorkerDeliveryAdapterRuntimeConfig runtime(
            String endpointManagerId,
            URI gatewayBaseUrl,
            Duration dispatchInterval,
            int scanCount
    ) {
        return new WorkerDeliveryAdapterRuntimeConfig(
                endpointManagerId,
                gatewayBaseUrl,
                Duration.ofSeconds(1),
                dispatchInterval,
                scanCount,
                100,
                1000
        );
    }
}
