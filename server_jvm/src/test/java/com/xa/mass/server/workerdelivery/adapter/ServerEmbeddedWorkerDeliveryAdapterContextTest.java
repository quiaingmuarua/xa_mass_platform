package com.xa.mass.server.workerdelivery.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterState;
import com.xa.mass.server.workerassembly
        .ServerWorkerAssemblyLifecycleHost;
import java.io.IOException;
import java.net.ServerSocket;
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
                "xa.mass.kernel.base-url=http://127.0.0.1:1",
                "xa.mass.kernel.connect-timeout=10ms",
                "xa.mass.kernel.read-timeout=10ms",
                "xa.mass.worker-delivery.adapter.http-client"
                        + ".base-url=http://127.0.0.1:1",
                "xa.mass.worker-delivery.adapter.http-client"
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
        registry.add(
                prefix + ".processes[0].type",
                () -> "DELIVERY_COMMAND"
        );
        registry.add(prefix + ".processes[0].interval", () -> "1h");
        registry.add(
                prefix + ".processes[0].queue-capacity",
                () -> "1000"
        );
        registry.add(
                prefix + ".processes[1].type",
                () -> "DELIVERY_REPORT"
        );
        registry.add(
                prefix + ".processes[1].queue-capacity",
                () -> "1000"
        );
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
    void serverStartsTheConcreteAdapterOwnedListener() {
        WorkerDeliveryAdapterManager manager = applicationContext.getBean(
                WorkerDeliveryAdapterManager.class
        );
        assertThat(manager.adapters()).containsOnlyKeys(
                "embedded-websocket"
        );
        WorkerDeliveryAdapter adapter =
                manager.requireAdapter("embedded-websocket");
        assertThat(adapter.adapterId()).isEqualTo("embedded-websocket");
        assertThat(adapter.state())
                .isEqualTo(WorkerDeliveryAdapterState.RUNNING);
        assertThat(applicationContext.getBean(
                ServerWorkerAssemblyLifecycleHost.class
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
