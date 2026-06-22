package com.xa.mass.transport.runtime;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.DeliveryPullResult;
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
                                binding("websocket", WorkerTransportHints.REALTIME),
                                binding("websocket", WorkerTransportHints.REALTIME)
                        )
                )
        );

        assertEquals("Duplicate worker adapter identity 'websocket' is registered more than once for adapter 'websocket'",
                error.getMessage());
    }

    @Test
    void transportBindingRequiresExplicitAdapterMailboxKey() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TransportBinding.builder(
                                "websocket",
                                WorkerTransportHints.REALTIME
                        )
                        .build()
        );

        assertEquals("adapterMailboxKey must not be blank", error.getMessage());
    }

    @Test
    void pullBindingRequiresSessionEvidenceDriver() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TransportBinding.builder(
                        "polling-custom",
                        WorkerTransportHints.POLLING
                        )
                        .adapterMailboxKey("polling-custom")
                        .deliveryPullChannel((deliveryBucketId, selectedWorkerId, maxMessages, timeoutMillis) ->
                                DeliveryPullResult.empty())
                        .build()
        );

        assertEquals("pullSessionEvidenceDriver must be set when deliveryPullChannel is set", error.getMessage());
    }

    private static TransportBinding binding(String adapterId, String transportHint) {
        return TransportBinding.builder(adapterId, transportHint)
                .adapterMailboxKey(adapterId)
                .build();
    }
}
