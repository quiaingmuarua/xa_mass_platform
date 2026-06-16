package com.xa.mass.starter.config;

import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.TransportOutboundMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportConfigTest {

    @Test
    void isEnabledRecognizesServerOnlyBundledAdapterState() {
        TransportConfig config = new TransportConfig();
        config.getBundledWebSocketAdapterConfig().setEnabled(false);
        config.getBundledWebSocketAdapterConfig().setServerEnabled(true);
        config.getBundledSocketAdapterConfig().setEnabled(false);
        config.getBundledSocketAdapterConfig().setServerEnabled(false);

        assertTrue(config.isEnabled());
        assertTrue(config.snapshotRuntimeComposition().isEnabled());
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

    private record StubBootstrap(String adapterId, String transportHint)
            implements TransportAdapterBootstrap {

        @Override
        public TransportAdapterDescriptor descriptor() {
            return new TransportAdapterDescriptor(adapterId, transportHint);
        }

        @Override
        public void contribute(TransportAdapterBootstrapContext context) {
        }
    }
}

