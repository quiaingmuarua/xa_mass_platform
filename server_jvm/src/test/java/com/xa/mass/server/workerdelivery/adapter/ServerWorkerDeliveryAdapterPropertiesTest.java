package com.xa.mass.server.workerdelivery.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterType;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

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
                WorkerDeliveryAdapterType.WEBSOCKET,
                runtime(
                        "websocket-adapter",
                        URI.create("file:///tmp/gateway"),
                        Duration.ofMillis(100)
                ),
                websocket()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP");

        assertThatThrownBy(() -> new ServerWorkerDeliveryAdapterProperties(
                true,
                WorkerDeliveryAdapterType.WEBSOCKET,
                runtime(
                        "websocket-adapter",
                        URI.create("http://127.0.0.1:18082"),
                        Duration.ZERO
                ),
                websocket()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dispatchInterval");
    }

    @Test
    void oldWebSocketSpecificNamespaceIsRejected() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                        "xa.mass.worker-delivery.adapter.websocket"
                                + ".enabled=true"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    private static ServerWorkerDeliveryAdapterProperties properties(
            boolean enabled,
            String endpointManagerId
    ) {
        return new ServerWorkerDeliveryAdapterProperties(
                enabled,
                WorkerDeliveryAdapterType.WEBSOCKET,
                runtime(
                        endpointManagerId,
                        URI.create("http://127.0.0.1:18082"),
                        Duration.ofMillis(100)
                ),
                websocket()
        );
    }

    private static ServerWorkerDeliveryAdapterProperties.RuntimeProperties
    runtime(
            String endpointManagerId,
            URI gatewayBaseUrl,
            Duration dispatchInterval
    ) {
        return new ServerWorkerDeliveryAdapterProperties.RuntimeProperties(
                endpointManagerId,
                gatewayBaseUrl,
                Duration.ofSeconds(1),
                dispatchInterval,
                100,
                100,
                1000
        );
    }

    private static ServerWorkerDeliveryAdapterProperties.WebSocketProperties
    websocket() {
        return new ServerWorkerDeliveryAdapterProperties.WebSocketProperties(
                Duration.ofSeconds(1)
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(
            ServerWorkerDeliveryAdapterProperties.class
    )
    static class PropertiesConfiguration {
    }
}
