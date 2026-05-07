package com.xa.mass.transport.websocket.dispatcher;

import com.google.gson.JsonObject;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.NoopWorkerSystemEventChannel;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        when(endpointRegistry.sendToAdapterRoute(org.mockito.ArgumentMatchers.eq("websocket"), org.mockito.ArgumentMatchers.eq("worker-1"), any()))
                .thenReturn(true);
        WebSocketTransportFrameCodec codec = new WebSocketTransportFrameCodec();
        WebSocketDispatcherContext context = new WebSocketDispatcherContext(
                "websocket",
                endpointRegistry,
                codec,
                null,
                NoopWorkerSystemEventChannel.INSTANCE
        );

        WebSocketTaskDispatchChannel publisher = new WebSocketTaskDispatchChannel(context, deliveryService());
        Task task = task();

        List<DispatchOutcome> outcomes = publisher.dispatchEnvelopes(List.of(envelope(TaskDispatchItem.from(
                TaskDispatchContext.from(task),
                binding()
        ))));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.SENT, outcomes.get(0).getStatus());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(endpointRegistry).sendToAdapterRoute(org.mockito.ArgumentMatchers.eq("websocket"), org.mockito.ArgumentMatchers.eq("worker-1"), captor.capture());

        JsonObject message = codec.parseObject(captor.getValue());
        assertNotNull(message);
        assertEquals("msg-1", message.get("messageId").getAsString());
        assertEquals("worker-1", message.get(TransportPacket.PAYLOAD_WORKER_ID).getAsString());
        assertEquals("task-1", message.get("taskId").getAsString());
        assertEquals("crawler.fetch-page", message.get("eventCode").getAsString());
        assertEquals("worker-context-1", message.get(TransportPacket.PAYLOAD_WORKER_CONTEXT_ID).getAsString());
        assertEquals("batch-0", message.get(TransportPacket.PAYLOAD_BATCH_ID).getAsString());
        assertEquals(0, message.get(TransportPacket.PAYLOAD_RETRY_COUNT).getAsInt());

        JsonObject input = message.getAsJsonObject(TransportPacket.PAYLOAD_INPUT);
        JsonObject sharedConfig = message.getAsJsonObject(TransportPacket.PAYLOAD_SHARED_CONFIG);
        assertNotNull(input);
        assertNotNull(sharedConfig);
        assertEquals("target-1", input.get("target").getAsString());
        assertEquals("demoApp", message.get(TransportPacket.PAYLOAD_PROJECT).getAsString());
        assertEquals("agent-1", message.get(TransportPacket.PAYLOAD_USER_ID).getAsString());
        assertEquals("hello", sharedConfig.get("textContent").getAsString());
    }

    @Test
    void returnsEndpointOfflineWhenEndpointRegistryCannotSend() {
        WorkerEndpointRegistry endpointRegistry = mock(WorkerEndpointRegistry.class);
        when(endpointRegistry.sendToAdapterRoute(org.mockito.ArgumentMatchers.eq("websocket"), org.mockito.ArgumentMatchers.eq("worker-1"), any()))
                .thenReturn(false);
        WebSocketTaskDispatchChannel publisher = new WebSocketTaskDispatchChannel(new WebSocketDispatcherContext(
                "websocket",
                endpointRegistry,
                new WebSocketTransportFrameCodec(),
                null,
                NoopWorkerSystemEventChannel.INSTANCE
        ), deliveryService());

        List<DispatchOutcome> outcomes = publisher.dispatchEnvelopes(List.of(envelope(TaskDispatchItem.from(
                TaskDispatchContext.from(task()),
                binding()
        ))));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.ENDPOINT_OFFLINE, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
    }

    @Test
    void returnsAdapterUnavailableWhenRuntimeContextIsIncomplete() {
        WebSocketTaskDispatchChannel publisher = new WebSocketTaskDispatchChannel(new WebSocketDispatcherContext(
                "websocket",
                null,
                new WebSocketTransportFrameCodec(),
                null,
                NoopWorkerSystemEventChannel.INSTANCE
        ), deliveryService());

        List<DispatchOutcome> outcomes = publisher.dispatchEnvelopes(List.of(envelope(TaskDispatchItem.from(
                TaskDispatchContext.from(task()),
                binding()
        ))));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.ADAPTER_UNAVAILABLE, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
    }

    @Test
    void returnsAdapterUnavailableWhenRuntimeContextIsMissing() {
        WebSocketTaskDispatchChannel publisher = new WebSocketTaskDispatchChannel(null, deliveryService());

        List<DispatchOutcome> outcomes = publisher.dispatchEnvelopes(List.of(envelope(TaskDispatchItem.from(
                TaskDispatchContext.from(task()),
                binding()
        ))));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.ADAPTER_UNAVAILABLE, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
    }

    private Task task() {
        Task task = new Task();
        task.setTid("task-1");
        task.setTaskName("task-name");
        task.setProject("demoApp");
        task.setUser(com.xa.mass.base.model.UserRef.of("agent-1"));
        task.setSharedConfig(java.util.Map.of(
                "textContent", "hello",
                "_sdk", java.util.Map.of("eventCode", "crawler.fetch-page")
        ));
        return task;
    }

    private TaskDispatchBinding binding() {
        return new TaskDispatchBinding(
                "task-1",
                "msg-1",
                "crawler.fetch-page",
                java.util.Map.of("target", "target-1"),
                null,
                0,
                "attempt-1",
                1,
                null,
                "worker-1",
                "worker-context-1",
                "batch-0"
        );
    }

    private TransportDeliveryService deliveryService() {
        return new TransportDeliveryService(new InMemoryTransportDeliveryStore());
    }

    private TransportDispatchEnvelope envelope(TaskDispatchItem item) {
        return new TransportDispatchEnvelope(
                "delivery-" + item.getMessageId(),
                new TransportPacket(
                        TransportPacket.CURRENT_VERSION,
                        "delivery-" + item.getMessageId(),
                        item.attemptId(),
                        PacketType.TASK_DISPATCH,
                        "websocket",
                        item.getWorkerId(),
                        item.getTaskId(),
                        item.getMessageId(),
                        item.attemptId(),
                        item.getEventCode(),
                        TransportPacket.JSON_CONTENT_TYPE,
                        item.toTransportPayload()
                ),
                1L
        );
    }
}

