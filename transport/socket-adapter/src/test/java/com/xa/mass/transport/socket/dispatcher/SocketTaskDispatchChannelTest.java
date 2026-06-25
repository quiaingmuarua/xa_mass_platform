package com.xa.mass.transport.socket.dispatcher;

import com.xa.mass.contract.worker.WorkerChannelFrame;
import com.xa.mass.contract.worker.WorkerChannelFrameJsonCodec;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
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
        StringWriter written = new StringWriter();
        SocketSessionManager sessionManager = sessionManagerWithWorker("worker-1", written);
        SocketTaskDispatchChannel channel = channel(sessionManager);

        List<DispatchOutcome> outcomes = channel.dispatch(List.of(request("msg-1", "worker-1")));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.DELIVERED, outcomes.get(0).getStatus());
        WorkerChannelFrame frame = new WorkerChannelFrameJsonCodec().decode(written.toString().trim());
        assertEquals(WorkerChannelFrame.ACTION, frame.kind());
        assertTrue(frame.body().contains("\"resultCorrelationRef\":\"corr-msg-1\""));
    }

    @Test
    void dispatchReturnsEndpointOfflineWhenWorkerSessionIsMissing() {
        SocketTaskDispatchChannel channel = channel(sessionManager());

        List<DispatchOutcome> outcomes = channel.dispatch(List.of(request("msg-1", "worker-1")));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
    }

    private SocketTaskDispatchChannel channel(SocketSessionManager sessionManager) {
        return new SocketTaskDispatchChannel(new SocketTransportFrameCodec(), sessionManager);
    }

    private SocketSessionManager sessionManagerWithWorker(String workerId, StringWriter written) {
        SocketSessionManager sessionManager = sessionManager();
        Socket socket = mock(Socket.class);
        when(socket.isConnected()).thenReturn(true);
        when(socket.isClosed()).thenReturn(false);
        sessionManager.addSession(
                "bucket-1",
                "socket-route",
                workerId,
                "endpoint-1",
                socket,
                new BufferedWriter(written)
        );
        return sessionManager;
    }

    private SocketSessionManager sessionManager() {
        return new SocketSessionManager(
                "socket",
                "socket",
                AdapterSessionEvidencePublisher.noop("socket", "socket")
        );
    }

    private DispatchMessage request(String correlationSuffix, String workerId) {
        return new DispatchMessage(
                "delivery-" + correlationSuffix,
                workerId,
                "{\"resultCorrelationRef\":\"corr-" + correlationSuffix + "\"}",
                "corr-" + correlationSuffix,
                0L,
                1L
        );
    }
}
