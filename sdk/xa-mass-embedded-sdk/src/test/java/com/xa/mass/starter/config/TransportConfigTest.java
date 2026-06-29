package com.xa.mass.starter.config;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.RuntimeTaskExecutorStatistics;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.runtime.InMemoryTransportResultIngressQueue;
import com.xa.mass.transport.runtime.TransportResultIngressQueue;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDispatchHandoff;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeEnvironment;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeSpec;
import com.xa.mass.transport.runtime.lease.CurrentSessionDisconnectSink;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import com.xa.mass.transport.starter.EmbeddedAdapterStarter;
import com.xa.mass.transport.starter.EmbeddedAdapterStarterDefaults;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketServerFactoryContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportConfigTest {

    @Test
    void isEnabledIgnoresServerOnlyBundledWebSocketAdapterState() {
        TransportConfig config = disabledConfig();
        config.getBundledWebSocketAdapterConfig().setServerEnabled(true);

        assertFalse(config.isEnabled());
        assertFalse(config.snapshotRuntimeComposition().isEnabled());
    }

    @Test
    void isEnabledRecognizesSupplementalBundledAdapterStateOnlyWhenAdapterEnabled() {
        TransportConfig config = disabledConfig();
        com.xa.mass.transport.socket.runtime.SocketAdapterConfig extraSocket =
                new com.xa.mass.transport.socket.runtime.SocketAdapterConfig();
        extraSocket.setAdapterId("socket-edge");
        extraSocket.setEnabled(false);
        extraSocket.setServerEnabled(true);
        config.addSupplementalSocketAdapterConfig(extraSocket);

        assertFalse(config.isEnabled());
        assertFalse(config.snapshotRuntimeComposition().isEnabled());

        extraSocket.setEnabled(true);
        config.addSupplementalSocketAdapterConfig(extraSocket);

        assertTrue(config.isEnabled());
        assertTrue(config.snapshotRuntimeComposition().isEnabled());
    }

    @Test
    void supplementalWebSocketServerFactoryStaysWithAdapterSpecFactory() {
        TransportConfig config = disabledConfig();
        WebSocketAdapterConfig extra = new WebSocketAdapterConfig();
        extra.setAdapterId("ws-extra");
        extra.setEnabled(true);
        extra.setServerEnabled(true);
        extra.setServerPort(19111);
        extra.setEndpointPath("/ws-extra");
        AtomicReference<WebSocketServerFactoryContext> capturedContext = new AtomicReference<>();

        config.addSupplementalWebSocketAdapterConfig(extra, context -> {
            capturedContext.set(context);
            return noopServer();
        });

        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();
        EmbeddedAdapterRuntimeSpec spec = runtimeComposition.resolveEmbeddedAdapterRuntimeSpecs().stream()
                .filter(candidate -> "ws-extra".equals(candidate.adapterId()))
                .findFirst()
                .orElseThrow();
        EmbeddedAdapterStarter starter = EmbeddedAdapterStarterDefaults.createStarter(
                environment(),
                runtimeComposition.resolvePollingPendingDeliveryBufferFactory(),
                runtimeComposition.resolveWebSocketServerFactoriesByAdapterId()
        );
        starter.create(runtimeComposition.resolveEmbeddedAdapterRuntimeSpecs());

        assertNotNull(capturedContext.get());
        assertEquals("ws-extra", spec.adapterId());
        assertEquals(19111, capturedContext.get().getPort());
        assertEquals("/ws-extra", capturedContext.get().getEndpointPath());
    }

    @Test
    void webSocketServerFactoryFailsFastWhenAdapterIsDisabled() {
        TransportConfig config = disabledConfig();
        WebSocketAdapterConfig extra = new WebSocketAdapterConfig();
        extra.setAdapterId("ws-disabled");
        extra.setEnabled(false);
        extra.setServerEnabled(true);

        config.addSupplementalWebSocketAdapterConfig(extra, context -> noopServer());

        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                runtimeComposition::resolveWebSocketServerFactoriesByAdapterId
        );
        assertTrue(error.getMessage().contains("disabled adapterId: ws-disabled"));
    }

    private static TransportConfig disabledConfig() {
        TransportConfig config = new TransportConfig();
        config.getBundledWebSocketAdapterConfig().setEnabled(false);
        config.getBundledWebSocketAdapterConfig().setServerEnabled(false);
        config.getBundledSocketAdapterConfig().setEnabled(false);
        config.getBundledSocketAdapterConfig().setServerEnabled(false);
        return config;
    }

    private static EmbeddedAdapterRuntimeEnvironment environment() {
        return new EmbeddedAdapterRuntimeEnvironment(
                new InMemoryTransportDispatchHandoff(10),
                new InMemoryTransportResultIngressQueue(10),
                new InMemoryTransportEndpointLeaseStore(),
                CurrentSessionDisconnectSink.NOOP,
                new DirectExecutor()
        );
    }

    private static TransportServer noopServer() {
        return new TransportServer() {
            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public boolean isRunning() {
                return true;
            }
        };
    }

    private static final class DirectExecutor implements RuntimeTaskExecutor {
        @Override
        public Future<?> submit(Runnable task) {
            task.run();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            try {
                return CompletableFuture.completedFuture(task.call());
            } catch (Exception e) {
                CompletableFuture<T> failed = new CompletableFuture<>();
                failed.completeExceptionally(e);
                return failed;
            }
        }

        @Override
        public void shutdown() {
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public RuntimeTaskExecutorStatistics getStatistics() {
            return new RuntimeTaskExecutorStatistics(0, 0, 0, 0, 0, 1);
        }
    }
}
