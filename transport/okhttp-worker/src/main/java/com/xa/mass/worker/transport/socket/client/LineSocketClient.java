package com.xa.mass.worker.transport.socket.client;

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
