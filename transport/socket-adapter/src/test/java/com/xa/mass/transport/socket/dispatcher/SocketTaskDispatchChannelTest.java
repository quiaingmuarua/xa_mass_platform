package com.xa.mass.transport.socket.dispatcher;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import com.xa.mass.transport.socket.session.SocketSessionManager;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.net.Socket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SocketTaskDispatchChannelTest {

    @Test
    void dispatchReturnsSentWhenWorkerSessionAcceptsMessage() {
        SocketSessionManager sessionManager = sessionManagerWithWorker("worker-1");
        SocketTaskDispatchChannel channel = channel(sessionManager);

        List<DispatchOutcome> outcomes = channel.dispatch(List.of(request("msg-1", "worker-1")));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.DELIVERED, outcomes.get(0).getStatus());
    }

    @Test
    void dispatchReturnsEndpointOfflineWhenWorkerSessionIsMissing() {
        SocketTaskDispatchChannel channel = channel(new SocketSessionManager("socket"));

        List<DispatchOutcome> outcomes = channel.dispatch(List.of(request("msg-1", "worker-1")));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
    }

    private SocketTaskDispatchChannel channel(SocketSessionManager sessionManager) {
        return new SocketTaskDispatchChannel(new SocketTransportFrameCodec(), sessionManager);
    }

    private SocketSessionManager sessionManagerWithWorker(String workerId) {
        SocketSessionManager sessionManager = new SocketSessionManager("socket");
        Socket socket = mock(Socket.class);
        when(socket.isConnected()).thenReturn(true);
        when(socket.isClosed()).thenReturn(false);
        sessionManager.addSession(
                "bucket-1",
                "socket-route",
                workerId,
                "endpoint-1",
                socket,
                new BufferedWriter(new StringWriter())
        );
        return sessionManager;
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

