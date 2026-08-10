package com.xa.mass.transport.client;

import java.util.Objects;

/**
 * Threadless reconnect attempt and stability state shared by text clients.
 */
public final class TextMessageReconnectState {

    public enum DisconnectAction {
        IGNORED,
        RECONNECT,
        TERMINATE
    }

    private final TextMessageReconnectPolicy policy;

    private long nextGeneration;
    private long activeGeneration;
    private int unstableAttempts;
    private boolean attemptActive;
    private boolean opened;
    private boolean stable;
    private boolean terminated;
    private boolean closed;

    public TextMessageReconnectState(TextMessageReconnectPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public synchronized long beginAttempt() {
        requireRunning();
        if (attemptActive) {
            throw new IllegalStateException(
                    "A text connection attempt is already active"
            );
        }
        activeGeneration = ++nextGeneration;
        attemptActive = true;
        opened = false;
        stable = false;
        return activeGeneration;
    }

    public synchronized boolean opened(long generation) {
        if (!isCurrent(generation) || opened) {
            return false;
        }
        opened = true;
        return true;
    }

    public synchronized boolean becameStable(long generation) {
        if (!isCurrent(generation) || !opened || stable) {
            return false;
        }
        stable = true;
        unstableAttempts = 0;
        return true;
    }

    public synchronized DisconnectAction disconnected(long generation) {
        if (!isCurrent(generation)) {
            return DisconnectAction.IGNORED;
        }
        attemptActive = false;
        opened = false;
        stable = false;
        unstableAttempts++;
        if (unstableAttempts >= policy.maxUnstableAttempts()) {
            terminated = true;
            return DisconnectAction.TERMINATE;
        }
        return DisconnectAction.RECONNECT;
    }

    public synchronized void close() {
        closed = true;
        attemptActive = false;
        opened = false;
        stable = false;
    }

    private boolean isCurrent(long generation) {
        return !closed
                && !terminated
                && attemptActive
                && activeGeneration == generation;
    }

    private void requireRunning() {
        if (closed) {
            throw new IllegalStateException(
                    "TextMessageReconnectState is closed"
            );
        }
        if (terminated) {
            throw new IllegalStateException(
                    "Text endpoint reconnect attempts are exhausted"
            );
        }
    }
}
