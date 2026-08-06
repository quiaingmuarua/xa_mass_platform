package com.xa.mass.worker.runtime;

import com.xa.mass.transport.client.TextMessageClient;
import java.util.Objects;

final class ObservedTextMessageClient implements TextMessageClient {

    interface Observer {

        void onOpen();

        void onDisconnected();

        void onFailure(Throwable error);
    }

    private final TextMessageClient delegate;
    private final Observer observer;

    ObservedTextMessageClient(
            TextMessageClient delegate,
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
            public void onMessage(String message) {
                listener.onMessage(message);
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
    public void closeCurrent(CloseReason reason) {
        delegate.closeCurrent(reason);
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
