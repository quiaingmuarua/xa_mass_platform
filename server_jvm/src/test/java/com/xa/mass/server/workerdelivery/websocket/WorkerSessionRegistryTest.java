package com.xa.mass.server.workerdelivery.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class WorkerSessionRegistryTest {

    @Test
    void newerConnectionReplacesOldWithoutOldDisconnectRemovingIt()
            throws Exception {
        WorkerSessionRegistry registry = registry();
        WebSocketSession oldSession = session("old");
        WebSocketSession newSession = session("new");

        long oldGeneration = registry.register("worker-1", oldSession);
        registry.register("worker-1", newSession);
        registry.unregister("worker-1", oldGeneration);

        assertThat(registry.activeSessionCount()).isEqualTo(1);
        assertThat(registry.send("worker-1", "command"))
                .isEqualTo(
                        WorkerSessionRegistry.DeliveryAttempt.DELIVERED
                );
        verify(oldSession).close(any(CloseStatus.class));
        verify(newSession).sendMessage(any(TextMessage.class));
    }

    @Test
    void missingSessionIsRejectedBeforeSend() {
        assertThat(registry().send("worker-1", "command"))
                .isEqualTo(
                        WorkerSessionRegistry.DeliveryAttempt
                                .REJECTED_BEFORE_SEND
                );
    }

    @Test
    void sendFailureBecomesUnknownAndRemovesTheSession() throws Exception {
        WorkerSessionRegistry registry = registry();
        WebSocketSession session = session("failed");
        doThrow(new IOException("failed"))
                .when(session)
                .sendMessage(any(TextMessage.class));
        registry.register("worker-1", session);

        assertThat(registry.send("worker-1", "command"))
                .isEqualTo(WorkerSessionRegistry.DeliveryAttempt.UNKNOWN);
        assertThat(registry.activeSessionCount()).isZero();
    }

    private static WorkerSessionRegistry registry() {
        return new WorkerSessionRegistry(properties(1000));
    }

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    static WorkerWebSocketProperties properties(int resultBufferCapacity) {
        return new WorkerWebSocketProperties(
                true,
                "websocket-adapter-1",
                Duration.ofMillis(100),
                100,
                100,
                resultBufferCapacity,
                Duration.ofSeconds(5)
        );
    }
}
