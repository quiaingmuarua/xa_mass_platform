package com.xa.mass.transport.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class TextMessageReconnectStateTest {

    @Test
    void unstableAttemptsTerminateAtConfiguredLimit() {
        TextMessageReconnectState state = state(2);

        long first = state.beginAttempt();
        assertEquals(
                TextMessageReconnectState.DisconnectAction.RECONNECT,
                state.disconnected(first)
        );
        long second = state.beginAttempt();
        assertEquals(
                TextMessageReconnectState.DisconnectAction.TERMINATE,
                state.disconnected(second)
        );
        assertThrows(IllegalStateException.class, state::beginAttempt);
    }

    @Test
    void stableConnectionResetsUnstableAttempts() {
        TextMessageReconnectState state = state(2);
        long first = state.beginAttempt();
        assertEquals(
                TextMessageReconnectState.DisconnectAction.RECONNECT,
                state.disconnected(first)
        );

        long stable = state.beginAttempt();
        assertTrue(state.opened(stable));
        assertTrue(state.becameStable(stable));
        assertEquals(
                TextMessageReconnectState.DisconnectAction.RECONNECT,
                state.disconnected(stable)
        );
    }

    @Test
    void staleAndRepeatedCallbacksAreIgnored() {
        TextMessageReconnectState state = state(3);
        long first = state.beginAttempt();
        assertTrue(state.opened(first));
        assertFalse(state.opened(first));
        assertEquals(
                TextMessageReconnectState.DisconnectAction.RECONNECT,
                state.disconnected(first)
        );
        assertEquals(
                TextMessageReconnectState.DisconnectAction.IGNORED,
                state.disconnected(first)
        );

        long second = state.beginAttempt();
        assertEquals(
                TextMessageReconnectState.DisconnectAction.IGNORED,
                state.disconnected(first)
        );
        assertEquals(
                TextMessageReconnectState.DisconnectAction.RECONNECT,
                state.disconnected(second)
        );
    }

    @Test
    void closeInvalidatesCurrentGeneration() {
        TextMessageReconnectState state = state(3);
        long generation = state.beginAttempt();

        state.close();

        assertEquals(
                TextMessageReconnectState.DisconnectAction.IGNORED,
                state.disconnected(generation)
        );
        assertThrows(IllegalStateException.class, state::beginAttempt);
    }

    private static TextMessageReconnectState state(int attempts) {
        return new TextMessageReconnectState(
                TextMessageReconnectPolicy.of(
                        attempts,
                        Duration.ofMillis(1),
                        Duration.ofMillis(10)
                )
        );
    }
}
