package com.xa.mass.transport.runtime;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.DeliveryPullResult;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
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
                                binding("websocket", WorkerTransportHints.REALTIME),
                                binding("websocket", WorkerTransportHints.REALTIME)
                        )
                )
        );

        assertEquals("Duplicate worker adapter identity 'websocket' is registered more than once for adapter 'websocket'",
                error.getMessage());
    }

    @Test
    void constructorRejectsSharedCommandExecutorAcrossAdapterBindings() {
        AdapterCommandExecutor sharedExecutor = commands -> java.util.List.of();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new TransportRuntimeRegistry(
                        mock(TransportResultIngressChannel.class),
                        new InMemoryTransportEndpointLeaseStore(),
                        List.of(
                                binding("websocket", WorkerTransportHints.REALTIME, sharedExecutor),
                                binding("socket", WorkerTransportHints.REALTIME, sharedExecutor)
                        )
                )
        );

        assertEquals("Adapter command executor instance is shared by adapters 'websocket' and 'socket'; "
                        + "each adapter binding must own a distinct executor instance",
                error.getMessage());
    }

    @Test
    void pullBindingRequiresSessionEvidenceDriver() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TransportBinding.builder(
                                "polling-custom",
                                WorkerTransportHints.POLLING,
                                commands -> java.util.List.of()
                        )
                        .deliveryPullChannel((deliveryBucketId, selectedWorkerId, maxMessages, timeoutMillis) ->
                                DeliveryPullResult.empty())
                        .build()
        );

        assertEquals("pullSessionEvidenceDriver must be set when deliveryPullChannel is set", error.getMessage());
    }

    private static TransportBinding binding(String adapterId, String transportHint) {
        return binding(adapterId, transportHint, commands -> java.util.List.of());
    }

    private static TransportBinding binding(String adapterId,
                                            String transportHint,
                                            AdapterCommandExecutor commandExecutor) {
        return TransportBinding.builder(adapterId, transportHint, commandExecutor)
                .build();
    }
}
