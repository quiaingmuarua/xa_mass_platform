package com.xa.mass.workerdelivery.adapter.websocket;

import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter.WorkerResultAcceptance.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter.WorkerResultAcceptance.BUFFER_FULL;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter.WorkerResultAcceptance;
import com.xa.mass.workerdelivery.adapter.application.WorkerSessionDirectory.WorkerSessionToken;
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
    private static final String SESSION_TOKEN_ATTRIBUTE =
            WorkerWebSocketHandler.class.getName() + ".sessionToken";
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
        WorkerSessionToken token = adapter.connectWorker(
                workerId(session),
                new SpringWebSocketWorkerConnection(
                        session,
                        codec,
                        sendTimeLimit
                )
        );
        session.getAttributes().put(SESSION_TOKEN_ATTRIBUTE, token);
    }

    @Override
    protected void handleTextMessage(
            WebSocketSession session,
            TextMessage message
    ) throws IOException {
        SeedResult result = codec.decodeSeedResult(message.getPayload());
        if (result == null) {
            close(session, CloseStatus.BAD_DATA);
            return;
        }
        WorkerResultAcceptance acceptance =
                adapter.acceptWorkerResult(token(session), result);
        if (acceptance == ACCEPTED || acceptance == BUFFER_FULL) {
            return;
        }
        close(
                session,
                acceptance == WorkerResultAcceptance.INVALID_OUTCOME
                        ? CloseStatus.BAD_DATA
                        : CloseStatus.POLICY_VIOLATION
        );
    }

    @Override
    protected void handleBinaryMessage(
            WebSocketSession session,
            BinaryMessage message
    ) {
        try {
            close(session, CloseStatus.NOT_ACCEPTABLE);
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
        Object token = session.getAttributes().get(SESSION_TOKEN_ATTRIBUTE);
        if (token instanceof WorkerSessionToken sessionToken) {
            adapter.disconnectWorker(sessionToken);
        }
    }

    private static WorkerSessionToken token(WebSocketSession session) {
        Object value = session.getAttributes().get(SESSION_TOKEN_ATTRIBUTE);
        if (!(value instanceof WorkerSessionToken token)) {
            throw new IllegalStateException(
                    "Worker WebSocket session has no session token"
            );
        }
        return token;
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
}
