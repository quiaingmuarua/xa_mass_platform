package com.xa.mass.workerdelivery.adapter.websocket;

import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter.WorkerResultAcceptance.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter.WorkerResultAcceptance.BUFFER_FULL;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason.RESULT_BUFFER_FULL;

import com.xa.mass.workerdelivery.adapter.application.WorkerConnection;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter.WorkerResultAcceptance;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

public final class WorkerWebSocketHandler extends TextWebSocketHandler {

    public static final String WORKER_PATH =
            "/api/v1/worker-delivery/websocket/workers/*";
    public static final String WORKER_PATH_PREFIX =
            "/api/v1/worker-delivery/websocket/workers/";
    public static final String WORKER_ID_ATTRIBUTE =
            WorkerWebSocketHandler.class.getName() + ".workerId";
    private static final String CONNECTION_ATTRIBUTE =
            WorkerWebSocketHandler.class.getName() + ".connection";
    private final WorkerDeliveryCodec codec;
    private final WorkerDeliveryAdapter adapter;
    private final Duration sendTimeLimit;

    public WorkerWebSocketHandler(
            WorkerDeliveryCodec codec,
            WorkerDeliveryAdapter adapter,
            Duration sendTimeLimit
    ) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.sendTimeLimit = Objects.requireNonNull(
                sendTimeLimit,
                "sendTimeLimit"
        );
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WorkerConnection connection = new SpringWebSocketWorkerConnection(
                session,
                codec,
                sendTimeLimit
        );
        session.getAttributes().put(CONNECTION_ATTRIBUTE, connection);
        adapter.connectWorker(workerId(session), connection);
    }

    @Override
    protected void handleTextMessage(
            WebSocketSession session,
            TextMessage message
    ) throws IOException {
        SeedResult result = codec.decodeSeedResult(message.getPayload());
        if (result == null) {
            disconnectAndClose(session, CloseStatus.BAD_DATA);
            return;
        }
        WorkerResultAcceptance acceptance =
                adapter.acceptWorkerResult(result);
        if (acceptance == ACCEPTED) {
            return;
        }
        if (acceptance == BUFFER_FULL) {
            disconnect(session);
            connection(session).close(RESULT_BUFFER_FULL);
            return;
        }
        disconnectAndClose(session, CloseStatus.BAD_DATA);
    }

    @Override
    protected void handleBinaryMessage(
            WebSocketSession session,
            BinaryMessage message
    ) {
        try {
            disconnectAndClose(session, CloseStatus.NOT_ACCEPTABLE);
        } catch (IOException ignored) {
            // Transport teardown is best effort.
        }
    }

    @Override
    public void handleTransportError(
            WebSocketSession session,
            Throwable exception
    ) throws IOException {
        disconnect(session);
        close(session, CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(
            WebSocketSession session,
            CloseStatus status
    ) {
        disconnect(session);
    }

    private void disconnect(WebSocketSession session) {
        Object connection = session.getAttributes().get(
                CONNECTION_ATTRIBUTE
        );
        Object workerId = session.getAttributes().get(WORKER_ID_ATTRIBUTE);
        if (connection instanceof WorkerConnection workerConnection
                && workerId instanceof String value
                && !value.isBlank()) {
            adapter.disconnectWorker(value, workerConnection);
        }
    }

    private static WorkerConnection connection(WebSocketSession session) {
        Object value = session.getAttributes().get(CONNECTION_ATTRIBUTE);
        if (!(value instanceof WorkerConnection connection)) {
            throw new IllegalStateException(
                    "Worker WebSocket session has no connection"
            );
        }
        return connection;
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

    private static void close(
            WebSocketSession session,
            CloseStatus status
    ) throws IOException {
        if (session.isOpen()) {
            session.close(status);
        }
    }

    private void disconnectAndClose(
            WebSocketSession session,
            CloseStatus status
    ) throws IOException {
        disconnect(session);
        close(session, status);
    }
}
