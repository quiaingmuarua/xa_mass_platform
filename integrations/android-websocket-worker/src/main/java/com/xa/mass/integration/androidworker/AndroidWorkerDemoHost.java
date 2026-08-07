package com.xa.mass.integration.androidworker;

import android.os.Handler;
import android.os.Looper;

import com.xa.mass.worker.android.AndroidWorker;
import com.xa.mass.worker.runtime.WorkerLifecycle;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

final class AndroidWorkerDemoHost implements AutoCloseable {

    interface Listener {

        void onSnapshot(Snapshot snapshot);
    }

    private final AndroidWorker worker;
    private final AndroidDemoStateCapability demoCapability;
    private final Handler mainHandler;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final WorkerLifecycle.Listener workerListener =
            ignored -> publish();
    private final AndroidDemoStateCapability.Listener capabilityListener =
            this::publish;

    private boolean closed;

    AndroidWorkerDemoHost(
            AndroidWorker worker,
            AndroidDemoStateCapability demoCapability
    ) {
        this(
                worker,
                demoCapability,
                new Handler(Looper.getMainLooper())
        );
    }

    AndroidWorkerDemoHost(
            AndroidWorker worker,
            AndroidDemoStateCapability demoCapability,
            Handler mainHandler
    ) {
        if (worker == null
                || demoCapability == null
                || mainHandler == null) {
            throw new IllegalArgumentException(
                    "Demo host dependencies must be present"
            );
        }
        this.worker = worker;
        this.demoCapability = demoCapability;
        this.mainHandler = mainHandler;
        worker.addListener(workerListener);
        demoCapability.addListener(capabilityListener);
    }

    void start() {
        requireOpen();
        worker.start();
    }

    void stop() {
        if (!closed) {
            worker.stop();
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
        WorkerLifecycle.Snapshot workerSnapshot = worker.snapshot();
        AndroidDemoStateCapability.Snapshot demo =
                demoCapability.snapshot();
        return new Snapshot(
                workerSnapshot.state(),
                workerSnapshot.prepareOperation(),
                workerSnapshot.connectionState(),
                workerSnapshot.workerId(),
                workerSnapshot.endpointUri(),
                demo.counter(),
                demo.processedCommands(),
                demo.lastEvent(),
                workerSnapshot.diagnosticMessage()
        );
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        worker.close();
        worker.removeListener(workerListener);
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

        private final WorkerLifecycle.State state;
        private final WorkerLifecycle.PrepareOperation prepareOperation;
        private final WorkerLifecycle.ConnectionState connectionState;
        private final String workerId;
        private final URI endpointUri;
        private final int counter;
        private final int processedCommands;
        private final String lastEvent;
        private final String errorMessage;

        private Snapshot(
                WorkerLifecycle.State state,
                WorkerLifecycle.PrepareOperation prepareOperation,
                WorkerLifecycle.ConnectionState connectionState,
                String workerId,
                URI endpointUri,
                int counter,
                int processedCommands,
                String lastEvent,
                String errorMessage
        ) {
            this.state = state;
            this.prepareOperation = prepareOperation;
            this.connectionState = connectionState;
            this.workerId = workerId;
            this.endpointUri = endpointUri;
            this.counter = counter;
            this.processedCommands = processedCommands;
            this.lastEvent = lastEvent;
            this.errorMessage = errorMessage;
        }

        WorkerLifecycle.State state() {
            return state;
        }

        WorkerLifecycle.PrepareOperation prepareOperation() {
            return prepareOperation;
        }

        WorkerLifecycle.ConnectionState connectionState() {
            return connectionState;
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
