package com.xa.mass.worker.android;

import com.xa.mass.transport.client.TextWebSocketClient;
import java.util.Objects;

final class ObservedTextWebSocketClient
        implements TextWebSocketClient {

    interface Observer {

        void onOpen();

        void onDisconnected();

        void onFailure(Throwable error);
    }

    private final TextWebSocketClient delegate;
    private final Observer observer;

    ObservedTextWebSocketClient(
            TextWebSocketClient delegate,
            Observer observer
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public void start(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        delegate.start(new Listener() {
            @Override
            public void onOpen() {
                listener.onOpen();
                observer.onOpen();
            }

            @Override
            public void onText(String message) {
                listener.onText(message);
            }

            @Override
            public void onBinary() {
                listener.onBinary();
            }

            @Override
            public void onDisconnected() {
                listener.onDisconnected();
                observer.onDisconnected();
            }

            @Override
            public void onFailure(Throwable error) {
                listener.onFailure(error);
                observer.onFailure(error);
            }
        });
    }

    @Override
    public boolean send(String message) {
        return delegate.send(message);
    }

    @Override
    public void closeCurrent(int code, String reason) {
        delegate.closeCurrent(code, reason);
    }

    @Override
    public boolean isConnected() {
        return delegate.isConnected();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
