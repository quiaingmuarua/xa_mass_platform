package com.xa.mass.server.workerdelivery.websocket;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass.ADAPTER_REJECTION;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
@ConditionalOnProperty(
        prefix = "xa.mass.worker-delivery.websocket",
        name = "enabled",
        havingValue = "true"
)
public final class WorkerWebSocketHandler extends TextWebSocketHandler {

    public static final String WORKER_ID_ATTRIBUTE =
            WorkerWebSocketHandler.class.getName() + ".workerId";
    private static final String GENERATION_ATTRIBUTE =
            WorkerWebSocketHandler.class.getName() + ".generation";
    private final WorkerDeliveryCodec codec;
    private final WorkerSessionRegistry sessions;
    private final WorkerDeliveryPump pump;

    public WorkerWebSocketHandler(
            WorkerDeliveryCodec codec,
            WorkerSessionRegistry sessions,
            WorkerDeliveryPump pump
    ) {
        this.codec = codec;
        this.sessions = sessions;
        this.pump = pump;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session)
            throws Exception {
        String workerId = workerId(session);
        long generation = sessions.register(workerId, session);
        session.getAttributes().put(GENERATION_ATTRIBUTE, generation);
    }

    @Override
    protected void handleTextMessage(
            WebSocketSession session,
            TextMessage message
    ) throws Exception {
        SeedResult result = codec.decodeSeedResult(message.getPayload());
        if (result == null
                || WorkerDeliveryProtocol.classifyOutcomeCode(
                        result.outcomeCode()
                ) == ADAPTER_REJECTION) {
            close(session, CloseStatus.BAD_DATA);
            return;
        }
        if (!pump.acceptWorkerResult(result)) {
            String workerId = workerId(session);
            long generation = generation(session);
            pump.closeForResultOverflow(workerId, generation);
        }
    }

    @Override
    protected void handleBinaryMessage(
            WebSocketSession session,
            BinaryMessage message
    ) {
        try {
            close(session, CloseStatus.NOT_ACCEPTABLE);
        } catch (IOException ignored) {
            // Session teardown is best effort.
        }
    }

    @Override
    public void handleTransportError(
            WebSocketSession session,
            Throwable exception
    ) throws Exception {
        unregister(session);
        close(session, CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(
            WebSocketSession session,
            CloseStatus status
    ) {
        unregister(session);
    }

    private void unregister(WebSocketSession session) {
        Object workerId = session.getAttributes().get(WORKER_ID_ATTRIBUTE);
        Object generation = session.getAttributes().get(GENERATION_ATTRIBUTE);
        if (workerId instanceof String id
                && generation instanceof Long value) {
            sessions.unregister(id, value);
        }
    }

    private static String workerId(WebSocketSession session) {
        Object value = session.getAttributes().get(WORKER_ID_ATTRIBUTE);
        if (!(value instanceof String workerId) || workerId.isBlank()) {
            throw new IllegalStateException(
                    "Worker WebSocket session has no Worker identity"
            );
        }
        return workerId;
    }

    private static long generation(WebSocketSession session) {
        Object value = session.getAttributes().get(GENERATION_ATTRIBUTE);
        if (!(value instanceof Long generation)) {
            throw new IllegalStateException(
                    "Worker WebSocket session has no generation"
            );
        }
        return generation;
    }

    private static void close(
            WebSocketSession session,
            CloseStatus status
    ) throws IOException {
        if (session.isOpen()) {
            session.close(status);
        }
    }
}
