package com.xa.mass.transport.runtime;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class TransportRuntimeRegistryTest {

    @Test
    void constructorRejectsDuplicateCanonicalAdapterIdAcrossBindings() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new TransportRuntimeRegistry(
                        mock(TransportResultIngressChannel.class),
                        new InMemoryTransportEndpointLeaseStore(),
                        List.of(
                                canonicalRouteBinding(new StubWorkerAdapter("websocket", WorkerTransportHints.REALTIME)),
                                canonicalRouteBinding(new StubWorkerAdapter("websocket", WorkerTransportHints.REALTIME))
                        )
                )
        );

        assertEquals("Duplicate worker adapter identity 'websocket' is registered more than once for adapter 'websocket'",
                error.getMessage());
    }

    private static TransportBinding canonicalRouteBinding(com.xa.mass.transport.worker.WorkerAdapter adapter) {
        return TransportBinding.builder(adapter)
                .build();
    }

    private static final class StubWorkerAdapter implements com.xa.mass.transport.worker.WorkerAdapter {
        private final String protocol;
        private final String transportHint;

        private StubWorkerAdapter(String protocol, String transportHint) {
            this.protocol = protocol;
            this.transportHint = transportHint;
        }

        @Override
        public String protocol() {
            return protocol;
        }

        @Override
        public String adapterId() {
            return protocol;
        }

        @Override
        public String transportHint() {
            return transportHint;
        }

        @Override
        public java.util.List<com.xa.mass.transport.model.DispatchOutcome> dispatch(
                java.util.List<com.xa.mass.transport.model.AdapterDispatchRequest> requests) {
            return java.util.List.of();
        }
    }
}
