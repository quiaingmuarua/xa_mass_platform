package com.xa.mass.workerdelivery.adapter.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WebSocketWorkerDeliveryAdapterConfig;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterRuntimeConfig;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterState;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterType;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WebSocketWorkerDeliveryAdapterFactoryTest {

    @Test
    void createsOneCompleteRegisteredWebSocketAdapter() {
        WebSocketWorkerDeliveryAdapterFactory factory =
                new WebSocketWorkerDeliveryAdapterFactory(
                        new WorkerDeliveryCodec()
                );

        WorkerDeliveryAdapter adapter = factory.create(
                runtimeConfig(),
                new WebSocketWorkerDeliveryAdapterConfig(
                        Duration.ofSeconds(1)
                )
        );

        assertThat(adapter.adapterType())
                .isEqualTo(WorkerDeliveryAdapterType.WEBSOCKET);
        assertThat(adapter.endpointManagerId())
                .isEqualTo("websocket-1");
        assertThat(adapter.state())
                .isEqualTo(WorkerDeliveryAdapterState.REGISTERED);
        assertThat(factory.handler()).isNotNull();

        assertThatThrownBy(() -> factory.create(
                runtimeConfig(),
                new WebSocketWorkerDeliveryAdapterConfig(
                        Duration.ofSeconds(1)
                )
        )).isInstanceOf(IllegalStateException.class);

        adapter.close();
    }

    @Test
    void handlerRequiresLocalRegistrationFirst() {
        WebSocketWorkerDeliveryAdapterFactory factory =
                new WebSocketWorkerDeliveryAdapterFactory(
                        new WorkerDeliveryCodec()
                );

        assertThatThrownBy(factory::handler)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not registered");
    }

    private static WorkerDeliveryAdapterRuntimeConfig runtimeConfig() {
        return new WorkerDeliveryAdapterRuntimeConfig(
                "websocket-1",
                URI.create("http://127.0.0.1:18082"),
                Duration.ofSeconds(1),
                Duration.ofMillis(100),
                100,
                100,
                1000
        );
    }
}
