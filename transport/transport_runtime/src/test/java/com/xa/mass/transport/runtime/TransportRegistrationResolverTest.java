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
    void resolvesSingleRealtimeFamilyWithoutExplicitAdapterId() {
        TransportRegistrationResolver resolver = new TransportRegistrationResolver(List.of(
                new TransportAdapterDescriptor("websocket", WorkerTransportHints.REALTIME)
        ));

        assertEquals("websocket", resolver.resolveRegistrationAdapterId(null, "realtime"));
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

        assertEquals("worker adapterId must be set when transportHint 'realtime' matches multiple adapters [socket, websocket]",
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
                binding("websocket", WorkerTransportHints.REALTIME)
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

    private static TransportBinding binding(String adapterId, String transportHint) {
        return TransportBinding.builder(adapterId, transportHint, commands -> java.util.List.of())
                .adapterMailboxKey(adapterId)
                .build();
    }
}
