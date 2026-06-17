package com.xa.mass.transport.websocket.dispatcher;

import com.google.gson.JsonObject;
import com.xa.mass.transport.RawWorkerRouteEndpointRegistry;
import com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        WebSocketTransportFrameCodec codec = new WebSocketTransportFrameCodec();
        WebSocketDispatcherContext context = new WebSocketDispatcherContext(
                "websocket",
                endpointRegistry,
                mock(RawWorkerRouteEndpointRegistry.class),
                codec,
                null
        );

        WebSocketTaskDispatchChannel publisher = new WebSocketTaskDispatchChannel(context, deliveryService());

        List<DispatchOutcome> outcomes = publisher.dispatch(List.of(request()));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.DELIVERED, outcomes.get(0).getStatus());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(endpointRegistry).sendToSelectedWorker(
                org.mockito.ArgumentMatchers.eq("websocket"),
                org.mockito.ArgumentMatchers.eq("worker-1"),
                captor.capture());

        JsonObject message = codec.parseObject(captor.getValue());
        assertNotNull(message);
        assertEquals("msg-1", message.get("messageId").getAsString());
        assertEquals("worker-1", message.get(TransportPacket.PAYLOAD_WORKER_ID).getAsString());
        assertEquals("task-1", message.get("taskId").getAsString());
        assertEquals("crawler.fetch-page", message.get("eventCode").getAsString());
        assertEquals("batch-0", message.get(TransportPacket.PAYLOAD_BATCH_ID).getAsString());
        assertEquals(0, message.get(TransportPacket.PAYLOAD_RETRY_COUNT).getAsInt());

        JsonObject input = message.getAsJsonObject(TransportPacket.PAYLOAD_INPUT);
        JsonObject sharedConfig = message.getAsJsonObject(TransportPacket.PAYLOAD_SHARED_CONFIG);
        assertNotNull(input);
        assertNotNull(sharedConfig);
        assertEquals("target-1", input.get("target").getAsString());
        assertFalse(message.has(TransportPacket.PAYLOAD_PROJECT));
        assertFalse(message.has(TransportPacket.PAYLOAD_TASK_NAME));
        assertFalse(message.has(TransportPacket.PAYLOAD_USER_ID));
        assertEquals("hello", sharedConfig.get("textContent").getAsString());
    }

    @Test
    void returnsEndpointOfflineWhenEndpointRegistryCannotSend() {
        WorkerEndpointRegistry endpointRegistry = mock(WorkerEndpointRegistry.class);
        when(endpointRegistry.sendToSelectedWorker(
                org.mockito.ArgumentMatchers.eq("websocket"),
                org.mockito.ArgumentMatchers.eq("worker-1"),
                any()))
                .thenReturn(false);
        WebSocketTaskDispatchChannel publisher = new WebSocketTaskDispatchChannel(new WebSocketDispatcherContext(
                "websocket",
                endpointRegistry,
                mock(RawWorkerRouteEndpointRegistry.class),
                new WebSocketTransportFrameCodec(),
                null
        ), deliveryService());

        List<DispatchOutcome> outcomes = publisher.dispatch(List.of(request()));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
    }

    @Test
    void returnsAdapterUnavailableWhenRuntimeContextIsIncomplete() {
        WebSocketTaskDispatchChannel publisher = new WebSocketTaskDispatchChannel(new WebSocketDispatcherContext(
                "websocket",
                null,
                mock(RawWorkerRouteEndpointRegistry.class),
                new WebSocketTransportFrameCodec(),
                null
        ), deliveryService());

        List<DispatchOutcome> outcomes = publisher.dispatch(List.of(request()));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.UNAVAILABLE, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
    }

    @Test
    void returnsAdapterUnavailableWhenRuntimeContextIsMissing() {
        WebSocketTaskDispatchChannel publisher = new WebSocketTaskDispatchChannel(null, deliveryService());

        List<DispatchOutcome> outcomes = publisher.dispatch(List.of(request()));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.UNAVAILABLE, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
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

