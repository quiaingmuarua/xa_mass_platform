package com.xa.mass.integration.androidworker;

import android.os.Handler;
import android.os.Looper;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

final class AndroidWorkerDemoHost implements AutoCloseable {

    interface Listener {

        void onSnapshot(Snapshot snapshot);
    }

    private final AndroidWebSocketWorkerPlugin workerPlugin;
    private final AndroidDemoStateCapability demoCapability;
    private final Handler mainHandler;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final AndroidWebSocketWorkerPlugin.Listener pluginListener =
            ignored -> publish();
    private final AndroidDemoStateCapability.Listener capabilityListener =
            this::publish;

    private boolean closed;

    AndroidWorkerDemoHost(
            AndroidWebSocketWorkerPlugin workerPlugin,
            AndroidDemoStateCapability demoCapability
    ) {
        this(
                workerPlugin,
                demoCapability,
                new Handler(Looper.getMainLooper())
        );
    }

    AndroidWorkerDemoHost(
            AndroidWebSocketWorkerPlugin workerPlugin,
            AndroidDemoStateCapability demoCapability,
            Handler mainHandler
    ) {
        if (workerPlugin == null
                || demoCapability == null
                || mainHandler == null) {
            throw new IllegalArgumentException(
                    "Demo host dependencies must be present"
            );
        }
        this.workerPlugin = workerPlugin;
        this.demoCapability = demoCapability;
        this.mainHandler = mainHandler;
        workerPlugin.addListener(pluginListener);
        demoCapability.addListener(capabilityListener);
    }

    void start() {
        requireOpen();
        workerPlugin.start();
    }

    void stop() {
        if (!closed) {
            workerPlugin.stop();
        }
    }

    int incrementCounter() {
        requireOpen();
        return demoCapability.incrementCounter();
    }

    int resetCounter() {
        requireOpen();
        return demoCapability.resetCounter();
    }

    void addListener(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must be present");
        }
        listeners.add(listener);
        deliver(listener, snapshot());
    }

    void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    Snapshot snapshot() {
        AndroidWebSocketWorkerPlugin.Snapshot worker =
                workerPlugin.snapshot();
        AndroidDemoStateCapability.Snapshot demo =
                demoCapability.snapshot();
        return new Snapshot(
                worker.state(),
                worker.workerId(),
                worker.endpointUri(),
                demo.counter(),
                demo.processedCommands(),
                demo.lastEvent(),
                worker.diagnosticMessage()
        );
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        workerPlugin.close();
        workerPlugin.removeListener(pluginListener);
        demoCapability.removeListener(capabilityListener);
        publish();
        listeners.clear();
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "AndroidWorkerDemoHost is closed"
            );
        }
    }

    private void publish() {
        Snapshot current = snapshot();
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                notifyListener(listener, current);
            }
        });
    }

    private void deliver(Listener listener, Snapshot current) {
        if (Looper.myLooper() == mainHandler.getLooper()) {
            notifyListener(listener, current);
        } else {
            mainHandler.post(() -> notifyListener(listener, current));
        }
    }

    private static void notifyListener(
            Listener listener,
            Snapshot snapshot
    ) {
        try {
            listener.onSnapshot(snapshot);
        } catch (RuntimeException ignored) {
            // UI observers do not own Worker or capability lifecycle.
        }
    }

    static final class Snapshot {

        private final AndroidWebSocketWorkerPlugin.State state;
        private final String workerId;
        private final URI endpointUri;
        private final int counter;
        private final int processedCommands;
        private final String lastEvent;
        private final String errorMessage;

        private Snapshot(
                AndroidWebSocketWorkerPlugin.State state,
                String workerId,
                URI endpointUri,
                int counter,
                int processedCommands,
                String lastEvent,
                String errorMessage
        ) {
            this.state = state;
            this.workerId = workerId;
            this.endpointUri = endpointUri;
            this.counter = counter;
            this.processedCommands = processedCommands;
            this.lastEvent = lastEvent;
            this.errorMessage = errorMessage;
        }

        AndroidWebSocketWorkerPlugin.State state() {
            return state;
        }

        String workerId() {
            return workerId;
        }

        URI endpointUri() {
            return endpointUri;
        }

        int counter() {
            return counter;
        }

        int processedCommands() {
            return processedCommands;
        }

        String lastEvent() {
            return lastEvent;
        }

        String errorMessage() {
            return errorMessage;
        }
    }
}
