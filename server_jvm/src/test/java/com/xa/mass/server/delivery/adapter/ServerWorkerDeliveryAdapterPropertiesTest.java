package com.xa.mass.server.delivery.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.xa.mass.server.worker.binding.WorkerEndpointDirectory;
import com.xa.mass.server.worker.binding.WorkerBindingService;
import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerDeliveryAdapterConfig;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context
        .ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ServerWorkerDeliveryAdapterPropertiesTest {

    private final ApplicationContextRunner contextRunner = contextRunner(true);

    @Test
    void bindsCompleteFlatAdapterConfigs() {
        List<String> properties = new ArrayList<>(List.of(
                "xa.mass.worker-delivery.adapter.remote-base-url="
                        + "http://127.0.0.1:19082",
                "xa.mass.worker-delivery.adapter.remote-request-timeout=20ms",
                "xa.mass.worker-delivery.adapter."
                        + "verification-queue-capacity=321",
                "xa.mass.worker-delivery.adapter.verification-timeout=30ms"
        ));
        properties.addAll(adapterProperties(
                "websocket-1",
                "WEBSOCKET",
                18083
        ));
        properties.addAll(adapterProperties("socket-1", "SOCKET", 18084));

        contextRunner.withPropertyValues(properties.toArray(String[]::new))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ServerWorkerDeliveryAdapterProperties bound =
                            context.getBean(
                                    ServerWorkerDeliveryAdapterProperties.class
                            );
                    assertThat(bound.remoteBaseUrl()).isEqualTo(
                            URI.create("http://127.0.0.1:19082")
                    );
                    assertThat(bound.remoteRequestTimeout()).isEqualTo(
                            Duration.ofMillis(20)
                    );
                    assertThat(bound.verificationQueueCapacity())
                            .isEqualTo(321);
                    assertThat(bound.verificationTimeout())
                            .isEqualTo(Duration.ofMillis(30));
                    assertThat(bound.instances())
                            .containsOnlyKeys("websocket-1", "socket-1");
                    NettyWorkerDeliveryAdapterConfig websocket =
                            bound.instances().get("websocket-1");
                    assertThat(websocket.type()).isEqualTo(
                            NettyWorkerDeliveryAdapterConfig.Type.WEBSOCKET
                    );
                    assertThat(websocket.commandBackoff()).isEqualTo(
                            Duration.ofMillis(100)
                    );
                    assertThat(websocket.shutdownTimeout()).isEqualTo(
                            Duration.ofSeconds(7)
                    );
                });
    }

    @Test
    void emptyInstancesRegistersNoActiveAdapter() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(
                    ServerWorkerDeliveryAdapterProperties.class
            ).instances()).isEmpty();
        });
    }

    @Test
    void rejectsIncompleteInvalidAndLegacyAdapterShapes() {
        assertFailed(
                "xa.mass.worker-delivery.adapter.instances.adapter-1"
                        + ".type=WEBSOCKET",
                "xa.mass.worker-delivery.adapter.instances.adapter-1"
                        + ".listen-port=18083"
        );
        assertAdapterFailed("type=OTHER");
        assertAdapterFailed("listen-port=0");
        assertAdapterFailed("command-consume-limit=0");
        assertAdapterFailed("command-consume-limit=1001");
        assertAdapterFailed("report-queue-capacity=1");
        assertAdapterFailed("reconnect-verification-retention=0ms");
        assertAdapterFailed("maximum-disconnected-workers=0");
        assertAdapterFailed("maximum-encoded-properties-bytes=0");
        assertAdapterFailed("shutdown-timeout=0ms");
        assertAdapterFailed("unexpected=true");
        assertAdapterFailed("processes[0].type=DELIVERY_COMMAND");
        assertAdapterFailed(
                "route-cache.reconnect-verification-retention=10m"
        );
        assertAdapterFailed(
                "properties-cache.maximum-encoded-bytes=1048576"
        );
        assertFailed(
                "xa.mass.worker-delivery.adapter.http-client"
                        + ".base-url=http://127.0.0.1:18082"
        );
    }

    @Test
    void validatesRemoteConfigurationWithoutAdapterDefaults() {
        assertThatThrownBy(() -> new ServerWorkerDeliveryAdapterProperties(
                URI.create("file:///not-http"),
                Duration.ofSeconds(1),
                100_000,
                Duration.ofSeconds(5),
                Map.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP");
        assertThatThrownBy(() -> new ServerWorkerDeliveryAdapterProperties(
                URI.create("http://127.0.0.1:18082?owner=other"),
                Duration.ofSeconds(1),
                100_000,
                Duration.ofSeconds(5),
                Map.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query or fragment");
        assertThatThrownBy(() -> new ServerWorkerDeliveryAdapterProperties(
                URI.create("http://127.0.0.1:18082"),
                Duration.ZERO,
                100_000,
                Duration.ofSeconds(5),
                Map.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remote-request-timeout");
        assertThatThrownBy(() -> new ServerWorkerDeliveryAdapterProperties(
                URI.create("http://127.0.0.1:18082"),
                Duration.ofSeconds(1),
                0,
                Duration.ofSeconds(5),
                Map.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verification-queue-capacity");
        assertThatThrownBy(() -> new ServerWorkerDeliveryAdapterProperties(
                URI.create("http://127.0.0.1:18082"),
                Duration.ofSeconds(1),
                100_000,
                Duration.ZERO,
                Map.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verification-timeout");
    }

    @Test
    void requiresMatchingWorkerBindingEndpoint() {
        contextRunner(false)
                .withPropertyValues(adapterProperties(
                        "adapter-1",
                        "WEBSOCKET",
                        18083
                ).toArray(String[]::new))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void loadedRecoveryOverlayOverridesOnlyItsReportFields() {
        Path overlay = Path.of(System.getProperty(
                "xa.mass.repository.root"
        )).resolve(
                "integrations/worker-loaded-recovery/server-config/"
                        + "application-worker-loaded-recovery.yaml"
        );
        contextRunner.withInitializer(
                new ConfigDataApplicationContextInitializer()
        ).withPropertyValues(
                "spring.profiles.active=scenario-workers",
                "spring.config.additional-location=" + overlay.toUri()
        ).run(context -> {
            assertThat(context).hasNotFailed();
            NettyWorkerDeliveryAdapterConfig config = context.getBean(
                    ServerWorkerDeliveryAdapterProperties.class
            ).instances().get("scenario-websocket");
            assertThat(config.commandBackoff()).isEqualTo(
                    Duration.ofMillis(100)
            );
            assertThat(config.commandRetryCapacity()).isEqualTo(1000);
            assertThat(config.reportBackoff()).isEqualTo(
                    Duration.ofMillis(100)
            );
            assertThat(config.reportQueueCapacity()).isEqualTo(20_000);
        });
    }

    private void assertAdapterFailed(String override) {
        List<String> properties = new ArrayList<>(adapterProperties(
                "adapter-1",
                "WEBSOCKET",
                18083
        ));
        properties.add(
                "xa.mass.worker-delivery.adapter.instances.adapter-1."
                        + override
        );
        assertFailed(properties.toArray(String[]::new));
    }

    private void assertFailed(String... properties) {
        contextRunner.withPropertyValues(properties)
                .run(context -> assertThat(context).hasFailed());
    }

    private static ApplicationContextRunner contextRunner(
            boolean endpointMatches
    ) {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        ServerWorkerDeliveryAdapterConfiguration.class
                )
                .withBean(WorkerEndpointDirectory.class, () -> {
                    WorkerEndpointDirectory directory = mock(
                            WorkerEndpointDirectory.class
                    );
                    org.mockito.Mockito.when(directory.contains(
                            org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.any()
                    )).thenReturn(endpointMatches);
                    return directory;
                })
                .withBean(
                        WorkerBindingService.class,
                        () -> mock(WorkerBindingService.class)
                );
    }

    private static List<String> adapterProperties(
            String adapterId,
            String type,
            int listenPort
    ) {
        String prefix = "xa.mass.worker-delivery.adapter.instances."
                + adapterId
                + ".";
        return List.of(
                prefix + "type=" + type,
                prefix + "listen-host=127.0.0.1",
                prefix + "listen-port=" + listenPort,
                prefix + "command-backoff=100ms",
                prefix + "command-consume-limit=100",
                prefix + "command-retry-capacity=1000",
                prefix + "report-backoff=1s",
                prefix + "report-queue-capacity=1000",
                prefix + "reconnect-verification-retention=10m",
                prefix + "maximum-disconnected-workers=100000",
                prefix + "maximum-encoded-properties-bytes=67108864",
                prefix + "send-time-limit=5s",
                prefix + "shutdown-timeout=7s"
        );
    }
}
