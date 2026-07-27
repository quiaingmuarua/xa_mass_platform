package com.xa.mass.workerdelivery.adapter.websocket;

import com.xa.mass.workerdelivery.adapter.application.WorkerConnection;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

public final class SpringWebSocketWorkerConnection
        implements WorkerConnection {

    private static final int SEND_BUFFER_LIMIT_BYTES = 1_048_576;
    private static final CloseStatus RESULT_BUFFER_FULL = new CloseStatus(
            1013,
            "Worker result buffer is full"
    );
    private final WebSocketSession session;
    private final WorkerDeliveryCodec codec;

    public SpringWebSocketWorkerConnection(
            WebSocketSession session,
            WorkerDeliveryCodec codec,
            Duration sendTimeLimit
    ) {
        Objects.requireNonNull(session, "session");
        this.codec = Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(sendTimeLimit, "sendTimeLimit");
        if (sendTimeLimit.isZero()
                || sendTimeLimit.isNegative()
                || sendTimeLimit.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "sendTimeLimit must be a positive int millis"
            );
        }
        this.session = new ConcurrentWebSocketSessionDecorator(
                session,
                Math.toIntExact(sendTimeLimit.toMillis()),
                SEND_BUFFER_LIMIT_BYTES
        );
    }

    @Override
    public CommandDeliveryAttempt deliver(
            WorkerCommandEnvelope command
    ) {
        Objects.requireNonNull(command, "command");
        if (!session.isOpen()) {
            return CommandDeliveryAttempt.REJECTED_BEFORE_SEND;
        }
        String encoded = codec.encodeWorkerCommand(command);
        try {
            session.sendMessage(new TextMessage(encoded));
            return CommandDeliveryAttempt.DELIVERED;
        } catch (IOException | RuntimeException error) {
            return CommandDeliveryAttempt.UNKNOWN;
        }
    }

    @Override
    public void close(WorkerConnectionCloseReason reason) {
        Objects.requireNonNull(reason, "reason");
        CloseStatus status = switch (reason) {
            case REPLACED -> new CloseStatus(
                    CloseStatus.POLICY_VIOLATION.getCode(),
                    "Replaced by a newer Worker session"
            );
            case RESULT_BUFFER_FULL -> RESULT_BUFFER_FULL;
            case TRANSPORT_ERROR -> CloseStatus.SERVER_ERROR;
            case ADAPTER_STOPPING -> CloseStatus.GOING_AWAY;
        };
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException ignored) {
            // Transport teardown is best effort.
        }
    }
}
