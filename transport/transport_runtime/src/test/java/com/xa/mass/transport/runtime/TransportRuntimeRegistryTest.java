package com.xa.mass.transport.runtime;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.runtime.route.InMemoryTransportRouteOwnerStore;
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
                        mock(TaskResultIngestChannel.class),
                        new InMemoryTransportRouteOwnerStore(),
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
                .routeKeyResolver((dispatchBinding, routeContext) -> "route:" + routeContext.workerId())
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
        public String transportHint() {
            return transportHint;
        }

        @Override
        public java.util.List<com.xa.mass.transport.model.DispatchOutcome> dispatchEnvelopes(
                java.util.List<com.xa.mass.transport.model.TransportDispatchEnvelope> envelopes) {
            return envelopes == null ? java.util.List.of() : envelopes.stream()
                    .map(envelope -> com.xa.mass.transport.model.DispatchOutcome.sent(adapterId(), envelope))
                    .toList();
        }
    }
}
