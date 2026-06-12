package com.xa.mass.transport.socket.dispatcher;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import com.xa.mass.transport.socket.session.SocketSessionManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SocketTaskDispatchChannelTest {

    @Test
    void dispatchReturnsSentWhenSessionManagerAcceptsMessage() {
        SocketSessionManager sessionManager = mock(SocketSessionManager.class);
        when(sessionManager.sendToSelectedWorker(
                org.mockito.ArgumentMatchers.eq("socket"),
                org.mockito.ArgumentMatchers.eq("worker-1"),
                any()))
                .thenReturn(true);
        SocketTaskDispatchChannel channel = channel(sessionManager);

        List<DispatchOutcome> outcomes = channel.dispatchEnvelopes(List.of(envelope(item("msg-1", "worker-1"))));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.DELIVERED, outcomes.get(0).getStatus());
        verify(sessionManager).sendToSelectedWorker(
                org.mockito.ArgumentMatchers.eq("socket"),
                org.mockito.ArgumentMatchers.eq("worker-1"),
                any());
    }

    @Test
    void dispatchReturnsEndpointOfflineWhenSessionManagerRejectsMessage() {
        SocketSessionManager sessionManager = mock(SocketSessionManager.class);
        when(sessionManager.sendToSelectedWorker(
                org.mockito.ArgumentMatchers.eq("socket"),
                org.mockito.ArgumentMatchers.eq("worker-1"),
                any()))
                .thenReturn(false);
        SocketTaskDispatchChannel channel = channel(sessionManager);

        List<DispatchOutcome> outcomes = channel.dispatchEnvelopes(List.of(envelope(item("msg-1", "worker-1"))));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
    }

    private TaskDispatchItem item(String messageId, String workerId) {
        return new TaskDispatchItem(
                "task-1",
                messageId,
                "crawler.fetch-page",
                "task-name",
                "demoApp",
                "agent",
                0,
                "attempt-" + messageId,
                workerId,
                "batch-1",
                Map.of("target", "target-1"),
                Map.of()
        );
    }

    private SocketTaskDispatchChannel channel(SocketSessionManager sessionManager) {
        return new SocketTaskDispatchChannel(
                "socket",
                sessionManager,
                new SocketTransportFrameCodec(),
                new TransportDeliveryService(new InMemoryTransportDeliveryStore())
        );
    }

    private TransportDispatchEnvelope envelope(TaskDispatchItem item) {
        return new TransportDispatchEnvelope(
                "delivery-" + item.getMessageId(),
                item.getWorkerId(),
                new TransportPacket(
                        TransportPacket.CURRENT_VERSION,
                        "delivery-" + item.getMessageId(),
                        item.attemptId(),
                        PacketType.TASK_DISPATCH,
                        "socket",
                        "group-route-1",
                        item.getTaskId(),
                        item.getMessageId(),
                        item.attemptId(),
                        item.getEventCode(),
                        TransportPacket.JSON_CONTENT_TYPE,
                        item.transportPayloadView()
                ),
                1L
        );
    }
}

