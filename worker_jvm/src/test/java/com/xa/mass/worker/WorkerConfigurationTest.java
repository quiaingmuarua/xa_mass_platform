package com.xa.mass.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkerConfigurationTest {

    @Test
    void pollingDefaultsAreStable() {
        WorkerConfiguration configuration = WorkerConfiguration.parse(
                new String[]{"--worker-id", "worker-1"}
        );

        assertEquals(WorkerTransportMode.POLLING, configuration.transport());
        assertEquals("worker-1", configuration.workerId());
        assertEquals(
                URI.create("http://127.0.0.1:18082"),
                configuration.serverUrl()
        );
        assertEquals(Duration.ofSeconds(5), configuration.requestTimeout());
        assertEquals(
                "system-polling",
                configuration.endpointManagerId()
        );
        assertEquals(Duration.ofMillis(500), configuration.pollInterval());
        assertNull(configuration.reconnectInterval());
    }

    @Test
    void websocketRejectsPollingOnlyOptions() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkerConfiguration.parse(new String[]{
                        "--transport", "websocket",
                        "--worker-id", "worker-1",
                        "--endpoint-manager-id", "adapter-1"
                })
        );
        WorkerConfiguration configuration = WorkerConfiguration.parse(
                new String[]{
                        "--transport", "websocket",
                        "--worker-id", "worker-1"
                }
        );
        assertEquals(
                Duration.ofSeconds(1),
                configuration.reconnectInterval()
        );
        assertEquals(
                URI.create("http://127.0.0.1:18083"),
                configuration.serverUrl()
        );
        assertNull(configuration.endpointManagerId());
        assertNull(configuration.pollInterval());
    }

    @Test
    void socketUsesTcpEndpointAndLongLivedReconnectPolicy() {
        WorkerConfiguration configuration = WorkerConfiguration.parse(
                new String[]{
                        "--transport", "socket",
                        "--worker-id", "worker-1"
                }
        );

        assertEquals(WorkerTransportMode.SOCKET, configuration.transport());
        assertEquals(
                URI.create("tcp://127.0.0.1:18084"),
                configuration.serverUrl()
        );
        assertEquals(
                Duration.ofSeconds(1),
                configuration.reconnectInterval()
        );
        assertNull(configuration.endpointManagerId());
        assertNull(configuration.pollInterval());
        assertThrows(IllegalArgumentException.class, () ->
                WorkerConfiguration.parse(new String[]{
                        "--transport", "socket",
                        "--worker-id", "worker-1",
                        "--server-url", "http://127.0.0.1:18084"
                })
        );
    }

    @Test
    void pollingRejectsWebsocketOnlyAndInvalidOptions() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkerConfiguration.parse(new String[]{
                        "--worker-id", "worker-1",
                        "--reconnect-interval-millis", "1"
                })
        );
        assertThrows(IllegalArgumentException.class, () ->
                WorkerConfiguration.parse(new String[]{
                        "--worker-id", "worker-1",
                        "--poll-interval-millis", "0"
                })
        );
        assertThrows(IllegalArgumentException.class, () ->
                WorkerConfiguration.parse(new String[]{
                        "--worker-id", "worker-1",
                        "--unknown", "value"
                })
        );
    }
}
