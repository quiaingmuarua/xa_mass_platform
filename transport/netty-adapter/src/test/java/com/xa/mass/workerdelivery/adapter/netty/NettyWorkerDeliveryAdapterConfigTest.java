package com.xa.mass.workerdelivery.adapter.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class NettyWorkerDeliveryAdapterConfigTest {

    @Test
    void acceptsTheTwoFixedAdapterTypes() {
        ConfigValues values = new ConfigValues();
        assertThat(values.build().type()).isEqualTo(
                NettyWorkerDeliveryAdapterConfig.Type.WEBSOCKET
        );

        values.type = NettyWorkerDeliveryAdapterConfig.Type.SOCKET;
        assertThat(values.build().type()).isEqualTo(
                NettyWorkerDeliveryAdapterConfig.Type.SOCKET
        );
    }

    @Test
    void validatesIdentityAndNetworkFields() {
        assertInvalid(values -> values.type = null);
        assertInvalid(values -> values.listenHost = " ");
        assertInvalid(values -> values.listenPort = 0);
        assertInvalid(values -> values.listenPort = 65_536);
        assertInvalid(values -> values.sendTimeLimit = Duration.ZERO);
        assertInvalid(values -> values.shutdownTimeout = Duration.ZERO);
    }

    @Test
    void validatesDispatcherFields() {
        assertInvalid(values -> values.commandBackoff = Duration.ZERO);
        assertInvalid(values -> values.commandConsumeLimit = 0);
        assertInvalid(values -> values.commandConsumeLimit = 1001);
        assertInvalid(values -> values.commandRetryCapacity = 0);
        assertInvalid(values ->
                values.commandRetryCapacity = Integer.MAX_VALUE
        );
        assertInvalid(values -> values.reportBackoff = Duration.ZERO);
        assertInvalid(values -> values.reportQueueCapacity = 1);
        assertInvalid(values ->
                values.reportQueueCapacity = Integer.MAX_VALUE
        );
    }

    @Test
    void validatesCacheFields() {
        assertInvalid(values ->
                values.reconnectVerificationRetention = Duration.ZERO
        );
        assertInvalid(values ->
                values.reconnectVerificationRetention = Duration.ofSeconds(
                        Long.MAX_VALUE
                )
        );
        assertInvalid(values -> values.maximumDisconnectedWorkers = 0L);
        assertInvalid(values -> values.maximumEncodedPropertiesBytes = 0L);
    }

    @Test
    void factoryCreatesBothTypesAndRejectsReservedIdentity() {
        NettyWorkerDeliveryAdapterFactory factory =
                new NettyWorkerDeliveryAdapterFactory(
                        URI.create("http://127.0.0.1:18082"),
                        Duration.ofSeconds(1)
                );
        ConfigValues values = new ConfigValues();

        assertThat(factory.create("websocket-1", values.build()))
                .isNotNull();
        values.type = NettyWorkerDeliveryAdapterConfig.Type.SOCKET;
        assertThat(factory.create("socket-1", values.build())).isNotNull();
        assertThatThrownBy(() -> factory.create(
                "system-polling",
                values.build()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertInvalid(Consumer<ConfigValues> mutation) {
        ConfigValues values = new ConfigValues();
        mutation.accept(values);
        assertThatThrownBy(values::build)
                .isInstanceOfAny(
                        IllegalArgumentException.class,
                        NullPointerException.class
                );
    }

    private static final class ConfigValues {

        private NettyWorkerDeliveryAdapterConfig.Type type =
                NettyWorkerDeliveryAdapterConfig.Type.WEBSOCKET;
        private String listenHost = "127.0.0.1";
        private int listenPort = 18083;
        private Duration commandBackoff = Duration.ofMillis(10);
        private int commandConsumeLimit = 100;
        private int commandRetryCapacity = 1000;
        private Duration reportBackoff = Duration.ofMillis(10);
        private int reportQueueCapacity = 1000;
        private Duration reconnectVerificationRetention =
                Duration.ofMinutes(10);
        private long maximumDisconnectedWorkers = 100_000L;
        private long maximumEncodedPropertiesBytes = 1024L;
        private Duration sendTimeLimit = Duration.ofSeconds(1);
        private Duration shutdownTimeout = Duration.ofSeconds(1);

        private NettyWorkerDeliveryAdapterConfig build() {
            return new NettyWorkerDeliveryAdapterConfig(
                    type,
                    listenHost,
                    listenPort,
                    commandBackoff,
                    commandConsumeLimit,
                    commandRetryCapacity,
                    reportBackoff,
                    reportQueueCapacity,
                    reconnectVerificationRetention,
                    maximumDisconnectedWorkers,
                    maximumEncodedPropertiesBytes,
                    sendTimeLimit,
                    shutdownTimeout
            );
        }
    }
}
