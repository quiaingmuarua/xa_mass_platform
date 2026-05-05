package com.xa.mass.transport.runtime;

import com.xa.mass.transport.WorkerTransportHints;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransportRegistrationResolverTest {

    @Test
    void resolvesSingleAdapterFamilyWithoutExplicitAdapterId() {
        TransportRegistrationResolver resolver = new TransportRegistrationResolver(List.of(
                new TransportAdapterDescriptor("polling", WorkerTransportHints.POLLING)
        ));

        assertEquals("polling", resolver.resolveRegistrationAdapterId(null, "polling"));
    }

    @Test
    void rejectsRealtimeFamilyWithoutExplicitAdapterIdEvenWhenOnlyOneAdapterExists() {
        TransportRegistrationResolver resolver = new TransportRegistrationResolver(List.of(
                new TransportAdapterDescriptor("websocket", WorkerTransportHints.REALTIME)
        ));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolveRegistrationAdapterId(null, "realtime")
        );

        assertEquals("worker adapterId must be set when transportHint 'realtime' is used",
                error.getMessage());
    }

    @Test
    void rejectsCompatibilityAliasAsAdapterId() {
        TransportRegistrationResolver resolver = new TransportRegistrationResolver(List.of(
                new TransportAdapterDescriptor("websocket", WorkerTransportHints.REALTIME)
        ));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolveRegistrationAdapterId("ws", "realtime")
        );

        assertEquals("Unsupported worker adapterId 'ws'; available adapterIds=[websocket]",
                error.getMessage());
        assertEquals("websocket", resolver.resolveRegistrationAdapterId(" websocket ", " realtime "));
    }

    @Test
    void rejectsAmbiguousTransportFamilyWithoutExplicitAdapterId() {
        TransportRegistrationResolver resolver = new TransportRegistrationResolver(List.of(
                new TransportAdapterDescriptor("websocket", WorkerTransportHints.REALTIME),
                new TransportAdapterDescriptor("socket", WorkerTransportHints.REALTIME)
        ));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolveRegistrationAdapterId(null, "realtime")
        );

        assertEquals("worker adapterId must be set when transportHint 'realtime' is used",
                error.getMessage());
    }

    @Test
    void rejectsExplicitAdapterIdWhenTransportHintFamilyDoesNotMatch() {
        TransportRegistrationResolver resolver = new TransportRegistrationResolver(List.of(
                new TransportAdapterDescriptor("polling", WorkerTransportHints.POLLING),
                new TransportAdapterDescriptor("websocket", WorkerTransportHints.REALTIME)
        ));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolveRegistrationAdapterId("websocket", "polling")
        );

        assertEquals("Worker adapterId 'websocket' belongs to transportHint 'realtime', not 'polling'",
                error.getMessage());
    }

    @Test
    void fromBindingsUsesCanonicalAdapterIdsOnly() {
        TransportRegistrationResolver resolver = TransportRegistrationResolver.fromBindings(List.of(
                TransportBinding.builder(new StubWorkerAdapter("websocket", WorkerTransportHints.REALTIME))
                        .build()
        ));

        assertEquals("websocket", resolver.resolveRegistrationAdapterId("websocket", "realtime"));
    }

    @Test
    void rejectsUnknownLegacyAdapterAliasEvenWhenAnotherAdapterExists() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new TransportRegistrationResolver(List.of(
                        new TransportAdapterDescriptor("websocket", WorkerTransportHints.REALTIME),
                        new TransportAdapterDescriptor("socket", WorkerTransportHints.REALTIME)
                )).resolveRegistrationAdapterId("ws", "realtime")
        );

        assertEquals("Unsupported worker adapterId 'ws'; available adapterIds=[socket, websocket]",
                error.getMessage());
    }

    @Test
    void rejectsDuplicateCanonicalAdapterIdAcrossDescriptors() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new TransportRegistrationResolver(List.of(
                        new TransportAdapterDescriptor("websocket", WorkerTransportHints.REALTIME),
                        new TransportAdapterDescriptor("websocket", WorkerTransportHints.REALTIME)
                ))
        );

        assertEquals("Duplicate worker adapter identity 'websocket' is registered more than once for adapter 'websocket'",
                error.getMessage());
    }

    private static final class StubWorkerAdapter implements com.xa.mass.engine.worker.WorkerAdapter {
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
