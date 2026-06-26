package com.xa.mass.starter.config;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.runtime.lease.CurrentSessionConnectSink;
import com.xa.mass.transport.runtime.lease.CurrentSessionDisconnectSink;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketServerFactoryContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TransportConfigTest {

    @Test
    void isEnabledIgnoresServerOnlyBundledWebSocketAdapterState() {
        TransportConfig config = new TransportConfig();
        config.getBundledWebSocketAdapterConfig().setEnabled(false);
        config.getBundledWebSocketAdapterConfig().setServerEnabled(true);
        config.getBundledSocketAdapterConfig().setEnabled(false);
        config.getBundledSocketAdapterConfig().setServerEnabled(false);

        assertFalse(config.isEnabled());
        assertFalse(config.snapshotRuntimeComposition().isEnabled());
    }

    @Test
    void isEnabledRecognizesCustomBootstrapWithoutBundledAdapters() {
        TransportConfig config = new TransportConfig();
        config.getBundledWebSocketAdapterConfig().setEnabled(false);
        config.getBundledWebSocketAdapterConfig().setServerEnabled(false);
        config.getBundledSocketAdapterConfig().setEnabled(false);
        config.getBundledSocketAdapterConfig().setServerEnabled(false);
        config.setPrimaryTransportAdapterBootstrap(new StubBootstrap("custom-rt", WorkerTransportHints.REALTIME));

        assertTrue(config.isEnabled());
        assertTrue(config.snapshotRuntimeComposition().isEnabled());
    }

    @Test
    void isEnabledRecognizesSupplementalBundledAdapterState() {
        TransportConfig config = new TransportConfig();
        config.getBundledWebSocketAdapterConfig().setEnabled(false);
        config.getBundledWebSocketAdapterConfig().setServerEnabled(false);
        config.getBundledSocketAdapterConfig().setEnabled(false);
        config.getBundledSocketAdapterConfig().setServerEnabled(false);

        com.xa.mass.transport.socket.runtime.SocketAdapterConfig extraSocket =
                new com.xa.mass.transport.socket.runtime.SocketAdapterConfig();
        extraSocket.setAdapterId("socket-edge");
        extraSocket.setEnabled(false);
        extraSocket.setServerEnabled(true);
        config.addSupplementalSocketAdapterConfig(extraSocket);

        assertTrue(config.isEnabled());
        assertTrue(config.snapshotRuntimeComposition().isEnabled());
    }

    @Test
    void supplementalWebSocketServerFactoryStaysWithAdapterAssembly() {
        TransportConfig config = new TransportConfig();
        config.getBundledWebSocketAdapterConfig().setEnabled(false);
        config.getBundledWebSocketAdapterConfig().setServerEnabled(false);
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
        TransportAdapterBootstrap bootstrap =
                runtimeComposition.resolveSupplementalBundledWebSocketTransportAdapterBootstraps().get(0);
        TransportAdapterContribution contribution = bootstrap.contribute(new TransportAdapterBootstrapContext(
                bootstrap.descriptor(),
                "ws-extra-mailbox",
                entry -> true,
                new InMemoryTransportEndpointLeaseStore(),
                CurrentSessionConnectSink.NOOP,
                CurrentSessionDisconnectSink.NOOP,
                mock(RuntimeTaskExecutor.class),
                null,
                null,
                null,
                1_000L
        ));

        assertEquals(1, contribution.getTransportServers().size());
        assertNotNull(capturedContext.get());
        assertEquals(19111, capturedContext.get().getPort());
        assertEquals("/ws-extra", capturedContext.get().getEndpointPath());
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

    private record StubBootstrap(String adapterId, String transportHint)
            implements TransportAdapterBootstrap {

        @Override
        public TransportAdapterDescriptor descriptor() {
            return new TransportAdapterDescriptor(adapterId, transportHint);
        }

        @Override
        public TransportAdapterContribution contribute(TransportAdapterBootstrapContext context) {
            return TransportAdapterContribution.empty();
        }
    }
}

