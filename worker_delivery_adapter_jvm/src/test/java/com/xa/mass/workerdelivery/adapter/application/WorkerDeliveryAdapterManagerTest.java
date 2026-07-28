package com.xa.mass.workerdelivery.adapter.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerDeliveryAdapterManagerTest {

    @Test
    void registersStartsAndClosesExactlyOneLocalAdapter() {
        FakeAdapter adapter = new FakeAdapter(false);
        WorkerDeliveryAdapterManager manager =
                new WorkerDeliveryAdapterManager(List.of(
                        new FakeFactory(adapter)
                ));

        assertThatThrownBy(manager::start)
                .isInstanceOf(IllegalStateException.class);

        manager.register(definition());
        assertThat(manager.state())
                .isEqualTo(WorkerDeliveryAdapterState.REGISTERED);

        manager.start();
        manager.start();
        assertThat(adapter.startCount).isEqualTo(1);
        assertThat(manager.state())
                .isEqualTo(WorkerDeliveryAdapterState.RUNNING);

        assertThatThrownBy(() -> manager.register(definition()))
                .isInstanceOf(IllegalStateException.class);

        manager.close();
        manager.close();
        assertThat(adapter.closeCount).isEqualTo(1);
        assertThat(manager.state())
                .isEqualTo(WorkerDeliveryAdapterState.CLOSED);
    }

    @Test
    void missingFactoryAndClosedManagerRejectRegistration() {
        WorkerDeliveryAdapterManager missingFactory =
                new WorkerDeliveryAdapterManager(List.of());

        assertThatThrownBy(() -> missingFactory.register(definition()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No factory");

        WorkerDeliveryAdapterManager closed =
                new WorkerDeliveryAdapterManager(List.of(
                        new FakeFactory(new FakeAdapter(false))
                ));
        closed.close();

        assertThatThrownBy(() -> closed.register(definition()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void startFailureClosesTheRegisteredAdapter() {
        FakeAdapter adapter = new FakeAdapter(true);
        WorkerDeliveryAdapterManager manager =
                new WorkerDeliveryAdapterManager(List.of(
                        new FakeFactory(adapter)
                ));
        manager.register(definition());

        assertThatThrownBy(manager::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("start failed");

        assertThat(adapter.closeCount).isEqualTo(1);
        assertThat(manager.state())
                .isEqualTo(WorkerDeliveryAdapterState.CLOSED);
    }

    private static WorkerDeliveryAdapterDefinition definition() {
        return new WorkerDeliveryAdapterDefinition(
                WorkerDeliveryAdapterType.WEBSOCKET,
                new WorkerDeliveryAdapterRuntimeConfig(
                        "websocket-1",
                        URI.create("http://127.0.0.1:18082"),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(100),
                        100,
                        100,
                        1000
                ),
                new WebSocketWorkerDeliveryAdapterConfig(
                        Duration.ofSeconds(1)
                )
        );
    }

    private static final class FakeFactory
            implements WorkerDeliveryAdapterFactory<
            WebSocketWorkerDeliveryAdapterConfig> {

        private final WorkerDeliveryAdapter adapter;

        private FakeFactory(WorkerDeliveryAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public WorkerDeliveryAdapterType adapterType() {
            return WorkerDeliveryAdapterType.WEBSOCKET;
        }

        @Override
        public Class<WebSocketWorkerDeliveryAdapterConfig>
        privateConfigType() {
            return WebSocketWorkerDeliveryAdapterConfig.class;
        }

        @Override
        public WorkerDeliveryAdapter create(
                WorkerDeliveryAdapterRuntimeConfig runtimeConfig,
                WebSocketWorkerDeliveryAdapterConfig privateConfig
        ) {
            return adapter;
        }
    }

    private static final class FakeAdapter
            implements WorkerDeliveryAdapter {

        private final boolean failStart;
        private WorkerDeliveryAdapterState state =
                WorkerDeliveryAdapterState.REGISTERED;
        private int startCount;
        private int closeCount;

        private FakeAdapter(boolean failStart) {
            this.failStart = failStart;
        }

        @Override
        public WorkerDeliveryAdapterType adapterType() {
            return WorkerDeliveryAdapterType.WEBSOCKET;
        }

        @Override
        public String endpointManagerId() {
            return "websocket-1";
        }

        @Override
        public WorkerDeliveryAdapterState state() {
            return state;
        }

        @Override
        public void start() {
            startCount++;
            if (failStart) {
                throw new IllegalStateException("start failed");
            }
            state = WorkerDeliveryAdapterState.RUNNING;
        }

        @Override
        public void close() {
            if (state == WorkerDeliveryAdapterState.CLOSED) {
                return;
            }
            closeCount++;
            state = WorkerDeliveryAdapterState.CLOSED;
        }
    }
}
