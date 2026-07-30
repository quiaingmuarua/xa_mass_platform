package com.xa.mass.transport.client;

/**
 * String-only line connection used by one Worker transport.
 *
 * <p>Implementations own connection and reconnect mechanics. They must not
 * retain Worker business messages for later delivery.
 */
public interface LineSocketClient extends AutoCloseable {

    void start(Listener listener);

    boolean sendLine(String message);

    boolean isConnected();

    @Override
    void close();

    interface Listener {

        void onOpen();

        void onLine(String message);

        void onDisconnected();

        void onFailure(Throwable error);
    }
}
