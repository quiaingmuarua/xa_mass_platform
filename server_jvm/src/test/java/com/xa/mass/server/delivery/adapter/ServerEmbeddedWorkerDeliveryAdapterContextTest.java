package com.xa.mass.server.delivery.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.server.assembly.runtime
        .ServerConfiguredRuntimeLifecycleHost;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("test")
@SpringBootTest(
        properties = {
                "xa.mass.worker-delivery.adapter"
                        + ".remote-base-url=http://127.0.0.1:1",
                "xa.mass.worker-delivery.adapter"
                        + ".remote-request-timeout=10ms"
        }
)
class ServerEmbeddedWorkerDeliveryAdapterContextTest {

    private static final int ADAPTER_PORT = availablePort();

    @Autowired
    private ApplicationContext applicationContext;

    @DynamicPropertySource
    static void adapterProperties(DynamicPropertyRegistry registry) {
        String prefix = "xa.mass.worker-delivery.adapter.instances"
                + ".embedded-websocket";
        registry.add(prefix + ".type", () -> "WEBSOCKET");
        registry.add(
                prefix + ".listen-host",
                () -> "127.0.0.1"
        );
        registry.add(
                prefix + ".listen-port",
                () -> Integer.toString(ADAPTER_PORT)
        );
        registry.add(
                prefix + ".command-backoff",
                () -> "1h"
        );
        registry.add(
                prefix + ".command-consume-limit",
                () -> "100"
        );
        registry.add(
                prefix + ".command-retry-capacity",
                () -> "1000"
        );
        registry.add(
                prefix + ".report-backoff",
                () -> "1s"
        );
        registry.add(
                prefix + ".report-queue-capacity",
                () -> "1000"
        );
        registry.add(
                prefix + ".reconnect-verification-retention",
                () -> "10m"
        );
        registry.add(
                prefix + ".maximum-disconnected-workers",
                () -> "100000"
        );
        registry.add(
                prefix + ".maximum-encoded-properties-bytes",
                () -> "67108864"
        );
        registry.add(prefix + ".send-time-limit", () -> "5s");
        registry.add(prefix + ".shutdown-timeout", () -> "5s");
        String endpoint = "xa.mass.worker-binding.endpoints"
                + ".embedded-websocket";
        registry.add(endpoint + ".transport-type", () -> "WEBSOCKET");
        registry.add(
                endpoint + ".public-uri",
                () -> "ws://127.0.0.1:" + ADAPTER_PORT
                        + "/api/v1/worker-delivery/websocket"
        );
    }

    @Test
    void serverStartsTheConcreteAdapterOwnedListener() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", ADAPTER_PORT)) {
            assertThat(socket.isConnected()).isTrue();
        }
        assertThat(applicationContext.getBean(
                ServerConfiguredRuntimeLifecycleHost.class
        )).isNotNull();
    }

    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Could not reserve an Adapter test port",
                    error
            );
        }
    }
}
