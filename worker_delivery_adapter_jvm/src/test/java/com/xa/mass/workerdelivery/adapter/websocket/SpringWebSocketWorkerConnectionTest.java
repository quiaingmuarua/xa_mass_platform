package com.xa.mass.workerdelivery.adapter.websocket;

import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt.DELIVERED;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt.REJECTED_BEFORE_SEND;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt.UNKNOWN;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason.RESULT_BUFFER_FULL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class SpringWebSocketWorkerConnectionTest {

    @Test
    void mapsWebSocketSendOutcomesWithoutCreatingEvidence() throws Exception {
        WebSocketSession open = session(true);
        SpringWebSocketWorkerConnection delivered = connection(open);

        assertThat(delivered.deliver(command())).isEqualTo(DELIVERED);
        verify(open).sendMessage(any(TextMessage.class));

        WebSocketSession closed = session(false);
        assertThat(connection(closed).deliver(command()))
                .isEqualTo(REJECTED_BEFORE_SEND);

        WebSocketSession failed = session(true);
        doThrow(new IOException("send failed"))
                .when(failed)
                .sendMessage(any(TextMessage.class));
        assertThat(connection(failed).deliver(command()))
                .isEqualTo(UNKNOWN);
    }

    @Test
    void mapsCoreCloseReasonsToTransportClose() throws Exception {
        WebSocketSession session = session(true);

        connection(session).close(RESULT_BUFFER_FULL);

        verify(session).close(any(CloseStatus.class));
    }

    private static SpringWebSocketWorkerConnection connection(
            WebSocketSession session
    ) {
        return new SpringWebSocketWorkerConnection(
                session,
                new WorkerDeliveryCodec(),
                Duration.ofSeconds(1)
        );
    }

    private static WebSocketSession session(boolean open) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(open);
        when(session.getId()).thenReturn("session-1");
        return session;
    }

    private static WorkerCommandEnvelope command() {
        return new WorkerCommandEnvelope(
                "a5e9e10d-f78b-469e-93ab-864b49c189c1",
                WorkerMessageType.TASK_ITEM,
                2_000,
                "{}"
        );
    }
}
