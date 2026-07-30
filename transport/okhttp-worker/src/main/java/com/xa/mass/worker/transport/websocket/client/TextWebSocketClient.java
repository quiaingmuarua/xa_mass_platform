package com.xa.mass.worker.transport.websocket.client;

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
