package com.xa.mass.workerdelivery.adapter.websocket;

import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter.WorkerResultAcceptance.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter.WorkerResultAcceptance.BUFFER_FULL;
import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter.WorkerResultAcceptance.INVALID_OUTCOME;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.workerdelivery.adapter.application.WorkerConnection;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class WorkerWebSocketHandlerTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private WorkerDeliveryAdapter adapter;
    private WorkerWebSocketHandler handler;
    private WebSocketSession session;

    @BeforeEach
    void setUp() {
        adapter = mock(WorkerDeliveryAdapter.class);
        handler = new WorkerWebSocketHandler(
                new WorkerDeliveryCodec(),
                adapter,
                Duration.ofSeconds(1)
        );
        session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        attributes.put(
                WorkerWebSocketHandler.WORKER_ID_ATTRIBUTE,
                "worker-1"
        );
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
    }

    @Test
    void translatesConnectionResultAndCloseIntoCoreCalls()
            throws Exception {
        handler.afterConnectionEstablished(session);
        ArgumentCaptor<WorkerConnection> connection =
                ArgumentCaptor.forClass(WorkerConnection.class);
        verify(adapter).connectWorker(
                eq("worker-1"),
                connection.capture()
        );
        SeedResult result = new SeedResult(
                COMMAND_ID,
                "context",
                "200",
                "null"
        );
        when(adapter.acceptWorkerResult(result))
                .thenReturn(ACCEPTED);

        handler.handleMessage(
                session,
                new TextMessage(
                        new WorkerDeliveryCodec().encodeSeedResult(result)
                )
        );
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(adapter).acceptWorkerResult(result);
        verify(adapter).disconnectWorker(
                eq("worker-1"),
                same(connection.getValue())
        );
        verify(session, never()).sendMessage(any());
    }

    @Test
    void closesInvalidOrAdapterOwnedResults() throws Exception {
        handler.afterConnectionEstablished(session);
        ArgumentCaptor<WorkerConnection> connection =
                ArgumentCaptor.forClass(WorkerConnection.class);
        verify(adapter).connectWorker(
                eq("worker-1"),
                connection.capture()
        );
        SeedResult rejection = new SeedResult(
                COMMAND_ID,
                "context",
                "3001",
                null
        );
        when(adapter.acceptWorkerResult(rejection))
                .thenReturn(INVALID_OUTCOME);

        handler.handleMessage(
                session,
                new TextMessage(
                        new WorkerDeliveryCodec().encodeSeedResult(rejection)
                )
        );

        verify(session).close(CloseStatus.BAD_DATA);
        verify(adapter).disconnectWorker(
                eq("worker-1"),
                same(connection.getValue())
        );
    }

    @Test
    void bufferFullUnbindsAndClosesTheResultConnection()
            throws Exception {
        handler.afterConnectionEstablished(session);
        SeedResult result = new SeedResult(
                COMMAND_ID,
                "context",
                "200",
                "null"
        );
        when(adapter.acceptWorkerResult(result)).thenReturn(BUFFER_FULL);
        ArgumentCaptor<WorkerConnection> connection =
                ArgumentCaptor.forClass(WorkerConnection.class);
        verify(adapter).connectWorker(
                eq("worker-1"),
                connection.capture()
        );

        handler.handleMessage(
                session,
                new TextMessage(
                        new WorkerDeliveryCodec().encodeSeedResult(result)
                )
        );

        verify(adapter).disconnectWorker(
                eq("worker-1"),
                same(connection.getValue())
        );
        verify(session).close(any(CloseStatus.class));
    }

    @Test
    void rejectsBinaryFrames() throws Exception {
        handler.handleMessage(
                session,
                new BinaryMessage(new byte[]{1})
        );

        verify(session).close(CloseStatus.NOT_ACCEPTABLE);
    }

    @Test
    void rejectsMalformedResultFramesBeforeCallingCore() throws Exception {
        handler.afterConnectionEstablished(session);
        ArgumentCaptor<WorkerConnection> connection =
                ArgumentCaptor.forClass(WorkerConnection.class);
        verify(adapter).connectWorker(
                eq("worker-1"),
                connection.capture()
        );

        handler.handleMessage(session, new TextMessage("{\"broken\":true}"));

        verify(session).close(CloseStatus.BAD_DATA);
        verify(adapter).disconnectWorker(
                eq("worker-1"),
                same(connection.getValue())
        );
        verify(adapter, never()).acceptWorkerResult(any());
    }
}
