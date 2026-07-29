package com.xa.mass.server.workerdelivery.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterState;
import com.xa.mass.workerdelivery.adapter.websocket.WebSocketWorkerDeliveryAdapter;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        properties = {
                "xa.mass.kernel.base-url=http://127.0.0.1:1",
                "xa.mass.kernel.connect-timeout=10ms",
                "xa.mass.kernel.read-timeout=10ms",
                "xa.mass.worker-delivery.adapter.gateway"
                        + ".base-url=http://127.0.0.1:1",
                "xa.mass.worker-delivery.adapter.gateway"
                        + ".request-timeout=10ms"
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
        registry.add(prefix + ".command-loop-interval", () -> "1h");
    }

    @Test
    void serverStartsTheConcreteAdapterOwnedListener() {
        WorkerDeliveryAdapterManager manager = applicationContext.getBean(
                WorkerDeliveryAdapterManager.class
        );
        assertThat(manager.adapters()).containsOnlyKeys(
                "embedded-websocket"
        );
        WebSocketWorkerDeliveryAdapter adapter =
                (WebSocketWorkerDeliveryAdapter)
                        manager.requireAdapter("embedded-websocket");
        assertThat(adapter.listenPort()).isEqualTo(ADAPTER_PORT);
        assertThat(adapter.state())
                .isEqualTo(WorkerDeliveryAdapterState.RUNNING);
        assertThat(applicationContext.getBean(
                WorkerDeliveryAdapterLifecycleHost.class
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
