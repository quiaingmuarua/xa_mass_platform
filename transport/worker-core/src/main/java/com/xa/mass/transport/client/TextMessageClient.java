package com.xa.mass.transport.client;

/**
 * Reconnecting, full-duplex text message connection used by one Worker.
 *
 * <p>The implementation owns network framing and reconnect mechanics. Listener
 * callbacks for one Client are ordered and never overlap. Callbacks from
 * superseded physical connections are suppressed. {@link #close()} prevents
 * new callbacks and waits for a callback already in progress, except that a
 * callback may reentrantly close its own Client.
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

    @Override
    void close();

    interface Listener {

        void onOpen();

        void onMessage(String message);

        /**
         * The current endpoint will not reconnect again. This terminates the
         * current Worker run; a host may explicitly start the Worker again.
         */
        void onEndpointTerminated();
    }
}
