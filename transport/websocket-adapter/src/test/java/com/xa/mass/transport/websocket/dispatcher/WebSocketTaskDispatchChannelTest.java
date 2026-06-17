package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketTaskDispatchChannelTest {

    @Test
    void publishesDispatchItemsDirectlyToEndpointRegistry() {
        WorkerEndpointRegistry endpointRegistry = mock(WorkerEndpointRegistry.class);
        when(endpointRegistry.sendToSelectedWorker(
                org.mockito.ArgumentMatchers.eq("websocket"),
                org.mockito.ArgumentMatchers.eq("worker-1"),
                any()))
                .thenReturn(true);
        WebSocketCommandDispatchContext context = new WebSocketCommandDispatchContext(
                "websocket",
                endpointRegistry
        );
        DeliveryCommand command = request();

        WebSocketTaskDispatchChannel publisher = new WebSocketTaskDispatchChannel(context, deliveryService());

        List<DispatchOutcome> outcomes = publisher.dispatch(List.of(command));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.DELIVERED, outcomes.get(0).getStatus());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(endpointRegistry).sendToSelectedWorker(
                org.mockito.ArgumentMatchers.eq("websocket"),
                org.mockito.ArgumentMatchers.eq("worker-1"),
                captor.capture());

        assertEquals(command.getPayload(), captor.getValue());
    }

    @Test
    void returnsEndpointOfflineWhenEndpointRegistryCannotSend() {
        WorkerEndpointRegistry endpointRegistry = mock(WorkerEndpointRegistry.class);
        when(endpointRegistry.sendToSelectedWorker(
                org.mockito.ArgumentMatchers.eq("websocket"),
                org.mockito.ArgumentMatchers.eq("worker-1"),
                any()))
                .thenReturn(false);
        WebSocketTaskDispatchChannel publisher = new WebSocketTaskDispatchChannel(new WebSocketCommandDispatchContext(
                "websocket",
                endpointRegistry
        ), deliveryService());

        List<DispatchOutcome> outcomes = publisher.dispatch(List.of(request()));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
    }

    @Test
    void returnsAdapterUnavailableWhenRuntimeContextIsIncomplete() {
        WebSocketTaskDispatchChannel publisher = new WebSocketTaskDispatchChannel(new WebSocketCommandDispatchContext(
                "websocket",
                null
        ), deliveryService());

        List<DispatchOutcome> outcomes = publisher.dispatch(List.of(request()));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.UNAVAILABLE, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
    }

    @Test
    void constructorRejectsMissingRuntimeContext() {
        assertThrows(NullPointerException.class,
                () -> new WebSocketTaskDispatchChannel(null, deliveryService()));
    }

    private TransportDeliveryService deliveryService() {
        return new TransportDeliveryService(new InMemoryTransportDeliveryStore());
    }

    private DeliveryCommand request() {
        return new DeliveryCommand(
                "delivery-msg-1",
                "bucket-1",
                "worker-1",
                """
                {
                  "messageId": "msg-1",
                  "workerId": "worker-1",
                  "taskId": "task-1",
                  "eventCode": "crawler.fetch-page",
                  "batchId": "batch-0",
                  "retryCount": 0,
                  "input": {"target": "target-1"},
                  "sharedConfig": {"textContent": "hello"}
                }
                """,
                "corr-msg-1",
                0L,
                1L
        );
    }
}

