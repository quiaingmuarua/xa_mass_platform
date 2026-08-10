package com.xa.mass.integration.androidworker;

import android.os.Handler;
import android.os.Looper;

import com.xa.mass.worker.runtime.WorkerLifecycle;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

final class AndroidWorkerDemoHost implements AutoCloseable {

    interface Listener {

        void onSnapshot(Snapshot snapshot);
    }

    private final WorkerLifecycle worker;
    private final AndroidDemoStateCapability demoCapability;
    private final AndroidWorkerDemoResources resources;
    private final Handler mainHandler;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final WorkerLifecycle.Listener workerListener =
            ignored -> publish();
    private final AndroidDemoStateCapability.Listener capabilityListener =
            this::publish;

    private final Object lock = new Object();
    private boolean desiredRunning;
    private boolean startScheduled;
    private boolean closed;

    AndroidWorkerDemoHost(
            WorkerLifecycle worker,
            AndroidDemoStateCapability demoCapability,
            AndroidWorkerDemoResources resources
    ) {
        this(
                worker,
                demoCapability,
                resources,
                new Handler(Looper.getMainLooper())
        );
    }

    AndroidWorkerDemoHost(
            WorkerLifecycle worker,
            AndroidDemoStateCapability demoCapability,
            AndroidWorkerDemoResources resources,
            Handler mainHandler
    ) {
        if (worker == null
                || demoCapability == null
                || resources == null
                || mainHandler == null) {
            throw new IllegalArgumentException(
                    "Demo host dependencies must be present"
            );
        }
        this.worker = worker;
        this.demoCapability = demoCapability;
        this.resources = resources;
        this.mainHandler = mainHandler;
        worker.addListener(workerListener);
        demoCapability.addListener(capabilityListener);
    }

    void start() {
        synchronized (lock) {
            requireOpenLocked();
            desiredRunning = true;
            if (startScheduled
                    || worker.snapshot().state()
                    == WorkerLifecycle.State.RUNNING) {
                return;
            }
            startScheduled = true;
            try {
                resources.controlExecutor().execute(
                        this::runStart
                );
            } catch (RuntimeException | Error failure) {
                startScheduled = false;
                throw failure;
            }
        }
    }

    void stop() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            desiredRunning = false;
        }
        worker.stop();
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
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            desiredRunning = false;
        }
        try {
            worker.close();
        } finally {
            worker.removeListener(workerListener);
            demoCapability.removeListener(capabilityListener);
            resources.close();
            publish();
            listeners.clear();
        }
    }

    private void requireOpen() {
        synchronized (lock) {
            requireOpenLocked();
        }
    }

    private void requireOpenLocked() {
        if (closed) {
            throw new IllegalStateException(
                    "AndroidWorkerDemoHost is closed"
            );
        }
    }

    private void runStart() {
        try {
            synchronized (lock) {
                if (closed || !desiredRunning) {
                    return;
                }
            }

            worker.start();

            boolean stopAfterStart;
            synchronized (lock) {
                stopAfterStart = closed || !desiredRunning;
            }
            if (stopAfterStart) {
                worker.stop();
            }
        } finally {
            synchronized (lock) {
                startScheduled = false;
            }
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
        private final String workerId;
        private final URI endpointUri;
        private final int counter;
        private final int processedCommands;
        private final String lastEvent;
        private final String errorMessage;

        private Snapshot(
                WorkerLifecycle.State state,
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

        WorkerLifecycle.State state() {
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
