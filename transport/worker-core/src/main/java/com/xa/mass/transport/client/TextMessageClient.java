package com.xa.mass.transport.client;

/**
 * Reconnecting, full-duplex text message connection used by one Worker.
 *
 * <p>The implementation owns network framing and reconnect mechanics. Listener
 * callbacks are serialized, callbacks from superseded connections are
 * suppressed, and no callback is emitted after {@link #close()} returns.
 * Transient disconnect and failure handling stays inside the Client. Once the
 * current endpoint will no longer reconnect, the Client emits exactly one
 * {@code onEndpointTerminated} callback and no later callback.
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

        /**
         * The current endpoint will not reconnect again. This is terminal for
         * this Client instance, not for the assembled Worker lifecycle.
         */
        void onEndpointTerminated();
    }
}
