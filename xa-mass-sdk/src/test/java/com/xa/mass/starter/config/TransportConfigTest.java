package com.xa.mass.starter.config;

import com.xa.mass.starter.transport.TransportAdapterBootstrap;
import com.xa.mass.starter.transport.TransportAdapterBootstrapContext;
import com.xa.mass.starter.transport.TransportAdapterContribution;
import com.xa.mass.starter.transport.TransportAdapterDescriptor;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.WorkerTransportMessage;
import org.junit.jupiter.api.Test;

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

    private record StubBootstrap(String adapterId, String transportHint)
            implements TransportAdapterBootstrap<WorkerTransportMessage> {

        @Override
        public TransportAdapterDescriptor descriptor() {
            return new TransportAdapterDescriptor(adapterId, transportHint, java.util.Set.of());
        }

        @Override
        public TransportAdapterContribution create(TransportAdapterBootstrapContext<WorkerTransportMessage> context) {
            return TransportAdapterContribution.builder().build();
        }
    }
}
