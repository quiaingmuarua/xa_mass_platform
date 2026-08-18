package com.xa.mass.server.workerdelivery.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.server.workerbinding.WorkerEndpointDirectory;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
    void bindsOrderedInstancesAndFiniteProcessDefaults() {
        contextRunner.withPropertyValues(
                "xa.mass.worker-delivery.adapter.http-client"
                        + ".base-url=http://127.0.0.1:18082",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".websocket-1.type=WEBSOCKET",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".websocket-1.listen-port=18083",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".websocket-1.route-cache"
                        + ".reconnect-verification-retention=2m",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".websocket-1.route-cache"
                        + ".maximum-disconnected-workers=2000",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".websocket-1.observation-cache.freshness=1m",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".websocket-1.observation-cache"
                        + ".maximum-encoded-bytes=1048576",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".websocket-1.processes[0].type=DELIVERY_COMMAND",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".websocket-1.processes[0]"
                        + ".queue-capacity=1000",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".websocket-1.processes[1].type=DELIVERY_REPORT",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".websocket-1.processes[1]"
                        + ".queue-capacity=1000",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".socket-1.type=SOCKET",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".socket-1.listen-host=127.0.0.1",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".socket-1.listen-port=18084",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".socket-1.processes[0].type=DELIVERY_REPORT",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".socket-1.processes[0]"
                        + ".queue-capacity=800",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".socket-1.processes[1].type=DELIVERY_COMMAND",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".socket-1.processes[1]"
                        + ".queue-capacity=1000",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".socket-1.processes[1]"
                        + ".consume-limit=100"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(
                    ServerWorkerDeliveryAdapterProperties.class
            ).instanceConfigs().keySet())
                    .containsExactly("websocket-1", "socket-1");
        });
    }

    @Test
    void emptyInstancesRegistersNoActiveAdapter() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(
                    ServerWorkerDeliveryAdapterProperties.class
            ).instanceConfigs()).isEmpty();
        });
    }

    @Test
    void rejectsInvalidAdapterAndProcessConfiguration() {
        assertAdapterFailed("type=OTHER");
        assertAdapterFailed("unexpected=true");
        assertAdapterFailed("listen-port=0");
        assertAdapterFailed("observation-freshness=5m");
        assertAdapterFailed(
                "route-cache.reconnect-verification-retention=0ms"
        );
        assertAdapterFailed("route-cache.verification-retention=2m");
        assertAdapterFailed("route-cache.maximum-disconnected-workers=0");
        assertAdapterFailed("route-cache.unknown=true");
        assertAdapterFailed("observation-cache.freshness=-1s");
        assertAdapterFailed("observation-cache.maximum-encoded-bytes=0");
        assertAdapterFailed("observation-cache.unknown=true");
        assertAdapterFailed(
                "processes[0].consume-limit=0"
        );
        assertAdapterFailed(
                "processes[0].consume-limit=101",
                "processes[0].queue-capacity=100"
        );
        assertAdapterFailed("processes[0].unknown=true");
        assertAdapterFailed("processes[0].lanes.unknown.queue-capacity=1");
        assertAdapterFailed("processes[1].type=DELIVERY_COMMAND");
        assertAdapterFailed("processes[1].type=UNKNOWN");
        assertAdapterFailed("processes[1].interval=0ms");
        assertAdapterFailed(
                "processes[1].retry-on-unavailable=maybe"
        );
        assertAdapterFailed("processes[0].type=TASK_COMMAND");
        assertFailed(
                "xa.mass.worker-delivery.adapter.instances"
                        + ".system-polling.type=WEBSOCKET",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".system-polling.listen-port=18083",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".system-polling.processes[0].type=DELIVERY_COMMAND",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".system-polling.processes[0]"
                        + ".queue-capacity=1000",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".system-polling.processes[1].type=DELIVERY_REPORT",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".system-polling.processes[1]"
                        + ".queue-capacity=1000",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".system-polling.processes[0]"
                        + ".consume-limit=100"
        );
    }

    @Test
    void rejectsMissingProcessesAndRemovedFlatConfiguration() {
        assertFailed(
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.type=WEBSOCKET",
                "xa.mass.worker-delivery.adapter.instances"
                        + ".adapter-1.listen-port=18083"
        );
        for (String field : new String[]{
                "command-loop-interval",
                "command-consume-limit",
                "command-queue-capacity",
                "result-submit-interval",
                "result-queue-capacity"
        }) {
            assertAdapterFailed(field + "=1");
        }
        assertFailed(
                "xa.mass.worker-delivery.adapter.gateway"
                        + ".base-url=http://127.0.0.1:18082"
        );
    }

    @Test
    void validatesSharedHttpClientConfiguration() {
        assertThatThrownBy(() ->
                new ServerWorkerDeliveryAdapterProperties(
                        new ServerWorkerDeliveryAdapterProperties
                                .HttpClientProperties(
                                URI.create("file:///not-http"),
                                Duration.ofSeconds(1)
                        ),
                        Map.of()
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP");
        assertThatThrownBy(() ->
                new ServerWorkerDeliveryAdapterProperties(
                        new ServerWorkerDeliveryAdapterProperties
                                .HttpClientProperties(
                                URI.create(
                                        "http://127.0.0.1:18082?owner=other"
                                ),
                                Duration.ofSeconds(1)
                        ),
                        Map.of()
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query or fragment");
        assertThatThrownBy(() ->
                new ServerWorkerDeliveryAdapterProperties(
                        new ServerWorkerDeliveryAdapterProperties
                                .HttpClientProperties(
                                URI.create("http://127.0.0.1:18082"),
                                Duration.ZERO
                        ),
                        Map.of()
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request-timeout");
    }

    private void assertAdapterFailed(String... overrides) {
        String prefix = "xa.mass.worker-delivery.adapter.instances.adapter-1.";
        List<String> properties = new ArrayList<>(List.of(
                prefix + "type=WEBSOCKET",
                prefix + "listen-port=18083",
                prefix + "processes[0].type=DELIVERY_COMMAND",
                prefix + "processes[0].queue-capacity=1000",
                prefix + "processes[1].type=DELIVERY_REPORT",
                prefix + "processes[1].queue-capacity=1000"
        ));
        for (String override : overrides) {
            properties.add(prefix + override);
        }
        assertFailed(properties.toArray(String[]::new));
    }

    private void assertFailed(String... properties) {
        contextRunner.withPropertyValues(properties)
                .run(context -> assertThat(context).hasFailed());
    }
}
