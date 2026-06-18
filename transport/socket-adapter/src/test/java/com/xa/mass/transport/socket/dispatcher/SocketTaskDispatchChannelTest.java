package com.xa.mass.transport.socket.dispatcher;

import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SocketTaskDispatchChannelTest {

    @Test
    void dispatchReturnsSentWhenEndpointRegistryAcceptsMessage() {
        WorkerEndpointRegistry endpointRegistry = mock(WorkerEndpointRegistry.class);
        when(endpointRegistry.sendToSelectedWorker(
                org.mockito.ArgumentMatchers.eq("worker-1"),
                any()))
                .thenReturn(true);
        SocketTaskDispatchChannel channel = channel(endpointRegistry);

        List<DispatchOutcome> outcomes = channel.dispatch(List.of(request("msg-1", "worker-1")));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.DELIVERED, outcomes.get(0).getStatus());
        verify(endpointRegistry).sendToSelectedWorker(
                org.mockito.ArgumentMatchers.eq("worker-1"),
                any());
    }

    @Test
    void dispatchReturnsEndpointOfflineWhenEndpointRegistryRejectsMessage() {
        WorkerEndpointRegistry endpointRegistry = mock(WorkerEndpointRegistry.class);
        when(endpointRegistry.sendToSelectedWorker(
                org.mockito.ArgumentMatchers.eq("worker-1"),
                any()))
                .thenReturn(false);
        SocketTaskDispatchChannel channel = channel(endpointRegistry);

        List<DispatchOutcome> outcomes = channel.dispatch(List.of(request("msg-1", "worker-1")));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
    }

    private SocketTaskDispatchChannel channel(WorkerEndpointRegistry endpointRegistry) {
        return new SocketTaskDispatchChannel(
                new SocketCommandDispatchContext("socket", endpointRegistry, new SocketTransportFrameCodec()),
                new TransportDeliveryService(new InMemoryTransportDeliveryStore())
        );
    }

    private DeliveryCommand request(String correlationSuffix, String workerId) {
        return new DeliveryCommand(
                "delivery-" + correlationSuffix,
                "bucket-1",
                workerId,
                "{\"resultCorrelationRef\":\"corr-" + correlationSuffix + "\"}",
                "corr-" + correlationSuffix,
                0L,
                1L
        );
    }
}

