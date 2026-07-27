package com.xa.mass.server.workerdelivery.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class WorkerWebSocketHandlerTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private WorkerDeliveryPump pump;
    private WorkerSessionRegistry sessions;
    private WorkerWebSocketHandler handler;
    private WebSocketSession session;

    @BeforeEach
    void setUp() {
        pump = mock(WorkerDeliveryPump.class);
        sessions = new WorkerSessionRegistry(
                WorkerSessionRegistryTest.properties(1000)
        );
        handler = new WorkerWebSocketHandler(
                new WorkerDeliveryCodec(),
                sessions,
                pump
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
    void acceptsWorkerOutcomeWithoutSendingAnAck() throws Exception {
        when(pump.acceptWorkerResult(any())).thenReturn(true);
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage(successResult()));

        verify(pump).acceptWorkerResult(new SeedResult(
                COMMAND_ID,
                "context",
                "200",
                "null"
        ));
        verify(session, never()).sendMessage(any());
    }

    @Test
    void closesWorkersThatForgeAdapterRejections() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleMessage(
                session,
                new TextMessage(
                        successResult()
                                .replace("\"200\"", "\"3001\"")
                                .replace("\"null\"", "null")
                )
        );

        verify(session).close(eq(CloseStatus.BAD_DATA));
        verify(pump, never()).acceptWorkerResult(any());
    }

    @Test
    void closesTheSessionWhenTheResultBufferIsFull() throws Exception {
        when(pump.acceptWorkerResult(any())).thenReturn(false);
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage(successResult()));

        verify(pump).closeForResultOverflow(eq("worker-1"), anyLong());
    }

    @Test
    void rejectsBinaryMessages() throws Exception {
        handler.handleMessage(session, new BinaryMessage(new byte[]{1}));

        verify(session).close(eq(CloseStatus.NOT_ACCEPTABLE));
    }

    private static String successResult() {
        return """
                {"commandId":"%s","opaqueResultContext":"context",\
                "opaqueResultPayload":"null","outcomeCode":"200"}\
                """.formatted(COMMAND_ID);
    }
}
