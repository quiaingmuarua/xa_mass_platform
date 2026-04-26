package com.xa.mass.transport.runtime;

import com.xa.mass.engine.WorkerManager;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class TransportRuntimeRegistryTest {

    @Test
    void constructorRejectsDuplicateAliasClaimedByDifferentBindings() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new TransportRuntimeRegistry(
                        mock(WorkerManager.class),
                        mock(TaskResultIngestChannel.class),
                        mock(WorkerSystemEventChannel.class),
                        List.of(
                                TransportBinding.builder(new StubWorkerAdapter("websocket", WorkerTransportHints.REALTIME, Set.of("ws")))
                                        .build(),
                                TransportBinding.builder(new StubWorkerAdapter("socket", WorkerTransportHints.REALTIME, Set.of("ws")))
                                        .build()
                        )
                )
        );

        assertEquals("Duplicate worker adapter identity 'ws' is claimed by adapters 'websocket' and 'socket'",
                error.getMessage());
    }

    @Test
    void constructorRejectsDuplicateCanonicalAdapterIdAcrossBindings() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new TransportRuntimeRegistry(
                        mock(WorkerManager.class),
                        mock(TaskResultIngestChannel.class),
                        mock(WorkerSystemEventChannel.class),
                        List.of(
                                TransportBinding.builder(new StubWorkerAdapter("websocket", WorkerTransportHints.REALTIME, Set.of("ws")))
                                        .build(),
                                TransportBinding.builder(new StubWorkerAdapter("websocket", WorkerTransportHints.REALTIME, Set.of("websocket-alt")))
                                        .build()
                        )
                )
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
        public void dispatchTaskItems(java.util.List<com.xa.mass.transport.model.TaskDispatchItem> items) {
        }
    }
}
