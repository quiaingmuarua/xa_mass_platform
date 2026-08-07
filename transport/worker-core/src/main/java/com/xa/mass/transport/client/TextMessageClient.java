package com.xa.mass.transport.client;

/**
 * Reconnecting, full-duplex text message connection used by one Worker.
 *
 * <p>The implementation owns network framing and reconnect mechanics. Listener
 * callbacks are serialized, callbacks from superseded connections are
 * suppressed, and no callback is emitted after {@link #close()} returns. Each
 * connection generation emits at most one terminal callback: either
 * {@code onDisconnected} or {@code onFailure}.
 * {@link #send(String)} is thread-safe and only reports whether the current
 * network stack accepted the message. Implementations do not cache Worker
 * business messages.
 */
public interface TextMessageClient extends AutoCloseable {

    enum CloseReason {
        NORMAL,
        PROTOCOL_ERROR,
        SEND_FAILURE
    }

    void start(Listener listener);

    boolean send(String message);

    /**
     * Closes only the current connection. A running client reconnects using
     * its existing endpoint and reconnect policy.
     */
    void closeCurrent(CloseReason reason);

    boolean isConnected();

    @Override
    void close();

    interface Listener {

        void onOpen();

        void onMessage(String message);

        void onDisconnected();

        void onFailure(Throwable error);

        /**
         * The current endpoint exhausted its consecutive unstable-connection
         * budget and will not reconnect again.
         */
        void onReconnectExhausted();
    }
}
