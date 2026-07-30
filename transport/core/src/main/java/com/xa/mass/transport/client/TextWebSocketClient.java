package com.xa.mass.transport.client;

/**
 * Text WebSocket connection boundary used by one Worker transport.
 *
 * <p>Listener callbacks are serialized. Callbacks from superseded
 * connections must not affect the current connection. {@link #send(String)}
 * is thread-safe and only reports whether the active network stack accepted
 * the message; implementations do not cache Worker business messages.
 * Lifecycle operations are idempotent, and no callbacks are emitted after
 * {@link #close()} returns.
 */
public interface TextWebSocketClient extends AutoCloseable {

    void start(Listener listener);

    boolean send(String message);

    void closeCurrent(int code, String reason);

    boolean isConnected();

    @Override
    void close();

    interface Listener {

        void onOpen();

        void onText(String message);

        void onBinary();

        void onDisconnected();

        void onFailure(Throwable error);
    }
}
