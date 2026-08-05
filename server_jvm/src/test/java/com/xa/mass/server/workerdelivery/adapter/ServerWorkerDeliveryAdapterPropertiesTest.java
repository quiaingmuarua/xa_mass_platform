package com.xa.mass.server.workerdelivery.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import com.xa.mass.workerdelivery.adapter.socket.SocketWorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.websocket.WebSocketWorkerDeliveryAdapter;
import com.xa.mass.server.workerbinding.WorkerEndpointDirectory;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ServerWorkerDeliveryAdapterPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            ServerWorkerDeliveryAdapterConfiguration.class
                    )
                    .withBean(WorkerEndpointDirectory.class, () -> {
                        WorkerEndpointDirectory directory =
                                org.mockito.Mockito.mock(
                                        WorkerEndpointDirectory.class
                                );
                        org.mockito.Mockito.when(directory.contains(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any()
                        )).thenReturn(true);
                        return directory;
                    });

    @Test
    void bindsOrderedWebSocketAndSocketInstancesAndAppliesDefaults() {
        contextRunner.withPropertyValues(
                "xa.mass.worker-delivery.adapter.gateway"
                        + ".base-url=http://127.0.0.1:18082",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".websocket-1.type=WEBSOCKET",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".websocket-1.listen-port=18083",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".socket-1.type=SOCKET",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".socket-1.listen-host=127.0.0.1",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".socket-1.listen-port=18084",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".socket-1.command-queue-capacity=800"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            WorkerDeliveryAdapterManager manager = context.getBean(
                    WorkerDeliveryAdapterManager.class
            );
            assertThat(manager.adapters().keySet())
                    .containsExactly("websocket-1", "socket-1");
            WebSocketWorkerDeliveryAdapter first =
                    (WebSocketWorkerDeliveryAdapter)
                            manager.requireAdapter("websocket-1");
            SocketWorkerDeliveryAdapter second =
                    (SocketWorkerDeliveryAdapter)
                            manager.requireAdapter("socket-1");
            assertThat(first.listenHost()).isEqualTo("0.0.0.0");
            assertThat(first.listenPort()).isEqualTo(18083);
            assertThat(second.listenHost()).isEqualTo("127.0.0.1");
            assertThat(second.listenPort()).isEqualTo(18084);
        });
    }

    @Test
    void emptyInstancesRegistersNoActiveAdapter() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(
                    WorkerDeliveryAdapterManager.class
            ).adapters()).isEmpty();
        });
    }

    @Test
    void rejectsUnknownTypeFieldInvalidPortAndPollingIdentity() {
        assertFailed(
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.type=OTHER",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.listen-port=18083"
        );
        assertFailed(
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.type=WEBSOCKET",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.listen-port=18083",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.unexpected=true"
        );
        assertFailed(
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.type=WEBSOCKET",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.listen-port=0"
        );
        assertFailed(
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.type=WEBSOCKET",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.listen-port=18083",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.command-consume-limit=0"
        );
        assertFailed(
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.type=WEBSOCKET",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.listen-port=18083",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.command-consume-limit=101",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.command-queue-capacity=100"
        );
        assertFailed(
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.type=WEBSOCKET",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.listen-port=18083",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.scan-count=100"
        );
        assertFailed(
                "xa.mass.worker-delivery.adapter.instances"
                        + ".system-polling.type=WEBSOCKET",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".system-polling.listen-port=18083"
        );
    }

    @Test
    void rejectsOldSingleInstanceConfiguration() {
        assertFailed(
                "xa.mass.worker-delivery.adapter.enabled=true",
                "xa.mass.worker-delivery.adapter.type=WEBSOCKET",
                "xa.mass.worker-delivery.adapter.runtime"
                        + ".endpoint-manager-id=adapter-1"
        );
    }

    @Test
    void rejectsOldLoopAndBufferFields() {
        for (String field : new String[]{
                "dispatch-interval",
                "delivery-parallelism",
                "result-batch-size",
                "result-buffer-capacity"
        }) {
            assertFailed(
                    "xa.mass.worker-delivery.adapter.instances"
                            + ".adapter-1.type=WEBSOCKET",
                    "xa.mass.worker-delivery.adapter.instances"
                            + ".adapter-1.listen-port=18083",
                    "xa.mass.worker-delivery.adapter.instances"
                            + ".adapter-1." + field + "=1"
            );
        }
    }

    @Test
    void validatesSharedGatewayConfiguration() {
        assertThatThrownBy(() ->
                new ServerWorkerDeliveryAdapterProperties(
                        new ServerWorkerDeliveryAdapterProperties
                                .GatewayProperties(
                                URI.create("file:///gateway"),
                                Duration.ofSeconds(1)
                        ),
                        Map.of()
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP");
        assertThatThrownBy(() ->
                new ServerWorkerDeliveryAdapterProperties(
                        new ServerWorkerDeliveryAdapterProperties
                                .GatewayProperties(
                                URI.create("http://127.0.0.1:18082"),
                                Duration.ZERO
                        ),
                        Map.of()
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request-timeout");
    }

    private void assertFailed(String... properties) {
        contextRunner.withPropertyValues(properties)
                .run(context -> assertThat(context).hasFailed());
    }
}
