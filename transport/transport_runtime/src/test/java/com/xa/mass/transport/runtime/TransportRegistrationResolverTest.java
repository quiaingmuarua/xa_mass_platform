package com.xa.mass.transport.runtime;

import com.xa.mass.transport.WorkerTransportHints;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransportRegistrationResolverTest {

    @Test
    void resolvesSingleAdapterFamilyWithoutExplicitAdapterId() {
        TransportRegistrationResolver resolver = new TransportRegistrationResolver(List.of(
                new TransportAdapterDescriptor("polling", WorkerTransportHints.POLLING, Set.of("pull", "queue"))
        ));

        assertEquals("polling", resolver.resolveRegistrationAdapterId(null, "polling"));
    }

    @Test
    void rejectsRealtimeFamilyWithoutExplicitAdapterIdEvenWhenOnlyOneAdapterExists() {
        TransportRegistrationResolver resolver = new TransportRegistrationResolver(List.of(
                new TransportAdapterDescriptor("websocket", WorkerTransportHints.REALTIME, Set.of("ws"))
        ));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolveRegistrationAdapterId(null, "realtime")
        );

        assertEquals("worker adapterId must be set when transportHint 'realtime' is used",
                error.getMessage());
    }

    @Test
    void resolvesCompatibilityAliasToCanonicalAdapterId() {
        TransportRegistrationResolver resolver = new TransportRegistrationResolver(List.of(
                new TransportAdapterDescriptor("websocket", WorkerTransportHints.REALTIME, Set.of("ws"))
        ));

        assertEquals("websocket", resolver.resolveRegistrationAdapterId("ws", "realtime"));
        assertEquals("websocket", resolver.resolveRegistrationAdapterId(" websocket ", " websocket "));
    }

    @Test
    void rejectsAmbiguousTransportFamilyWithoutExplicitAdapterId() {
        TransportRegistrationResolver resolver = new TransportRegistrationResolver(List.of(
                new TransportAdapterDescriptor("websocket", WorkerTransportHints.REALTIME, Set.of("ws")),
                new TransportAdapterDescriptor("socket", WorkerTransportHints.REALTIME, Set.of("tcp-socket"))
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
                new TransportAdapterDescriptor("polling", WorkerTransportHints.POLLING, Set.of("pull", "queue")),
                new TransportAdapterDescriptor("websocket", WorkerTransportHints.REALTIME, Set.of("ws"))
        ));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolveRegistrationAdapterId("websocket", "polling")
        );

        assertEquals("Worker adapterId 'websocket' belongs to transportHint 'realtime', not 'polling'",
                error.getMessage());
    }

    @Test
    void fromBindingsUsesWorkerAdapterAliasesAndCanonicalIds() {
        TransportRegistrationResolver resolver = TransportRegistrationResolver.fromBindings(List.of(
                TransportBinding.builder(new StubWorkerAdapter("websocket", WorkerTransportHints.REALTIME, Set.of("ws")))
                        .build()
        ));

        assertEquals("websocket", resolver.resolveRegistrationAdapterId("ws", "realtime"));
    }

    @Test
    void rejectsDuplicateAliasClaimedByDifferentAdapters() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new TransportRegistrationResolver(List.of(
                        new TransportAdapterDescriptor("websocket", WorkerTransportHints.REALTIME, Set.of("ws")),
                        new TransportAdapterDescriptor("socket", WorkerTransportHints.REALTIME, Set.of("ws"))
                ))
        );

        assertEquals("Duplicate worker adapter identity 'ws' is claimed by adapters 'websocket' and 'socket'",
                error.getMessage());
    }

    @Test
    void rejectsDuplicateCanonicalAdapterIdAcrossDescriptors() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new TransportRegistrationResolver(List.of(
                        new TransportAdapterDescriptor("websocket", WorkerTransportHints.REALTIME, Set.of("ws")),
                        new TransportAdapterDescriptor("websocket", WorkerTransportHints.REALTIME, Set.of("websocket-alt"))
                ))
        );

        assertEquals("Duplicate worker adapter identity 'websocket' is registered more than once for adapter 'websocket'",
                error.getMessage());
    }

    private static final class StubWorkerAdapter implements com.xa.mass.engine.worker.WorkerAdapter {
        private final String protocol;
        private final String transportHint;
        private final Set<String> aliases;

        private StubWorkerAdapter(String protocol, String transportHint, Set<String> aliases) {
            this.protocol = protocol;
            this.transportHint = transportHint;
            this.aliases = aliases;
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
        public Set<String> aliases() {
            return aliases;
        }

        @Override
        public java.util.List<com.xa.mass.transport.model.DispatchOutcome> dispatchTaskItems(
                java.util.List<com.xa.mass.transport.model.TaskDispatchItem> items) {
            return items == null ? java.util.List.of() : items.stream()
                    .map(item -> com.xa.mass.transport.model.DispatchOutcome.sent(adapterId(), item))
                    .toList();
        }
    }
}
