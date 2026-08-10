package com.xa.mass.worker.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Coordinates one explicitly requested Worker run.
 *
 * <p>Each accepted start performs one preparation and installs at most one
 * Transport. Endpoint termination ends the run; only the host can start
 * another.
 */
public final class WorkerRunController implements WorkerLifecycle {

    private enum Phase {
        STOPPED,
        STARTING,
        ACTIVE,
        STOPPING,
        CLOSED
    }

    private final Object lock = new Object();
    private final WorkerPreparation preparation;
    private final TextMessageWorkerTransportFactory transportFactory;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();

    private Phase phase = Phase.STOPPED;
    private PreparedWorker preparedWorker;
    private TextMessageWorkerTransport activeTransport;
    private String diagnosticMessage;

    public WorkerRunController(
            WorkerPreparation preparation,
            TextMessageWorkerTransportFactory transportFactory
    ) {
        this.preparation = Objects.requireNonNull(
                preparation,
                "preparation"
        );
        this.transportFactory = Objects.requireNonNull(
                transportFactory,
                "transportFactory"
        );
    }

    @Override
    public void start() {
        synchronized (lock) {
            if (phase == Phase.CLOSED) {
                throw new IllegalStateException(
                        "WorkerRunController is closed"
                );
            }
            if (phase != Phase.STOPPED) {
                return;
            }
            phase = Phase.STARTING;
            preparedWorker = null;
            activeTransport = null;
            diagnosticMessage = null;
        }
        runStart();
    }

    @Override
    public void stop() {
        TextMessageWorkerTransport transport;
        synchronized (lock) {
            if (phase == Phase.STOPPED
                    || phase == Phase.STOPPING
                    || phase == Phase.CLOSED) {
                return;
            }
            phase = Phase.STOPPING;
            transport = activeTransport;
        }
        if (transport != null) {
            transport.requestStop();
        }
    }

    @Override
    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(
                    lifecycleStateLocked(),
                    preparedWorker == null
                            ? null
                            : preparedWorker.workerId(),
                    preparedWorker == null
                            ? null
                            : preparedWorker.endpointUri(),
                    diagnosticMessage
            );
        }
    }

    @Override
    public void addListener(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lock) {
            if (phase == Phase.CLOSED) {
                throw new IllegalStateException(
                        "WorkerRunController is closed"
                );
            }
            listeners.add(listener);
        }
        notifyListener(listener, snapshot());
    }

    @Override
    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    @Override
    public void close() {
        TextMessageWorkerTransport transport;
        synchronized (lock) {
            if (phase == Phase.CLOSED) {
                return;
            }
            phase = Phase.CLOSED;
            transport = activeTransport;
            activeTransport = null;
            preparedWorker = null;
            diagnosticMessage = null;
        }

        closeQuietly(transport);
        closeQuietly(preparation);
        Snapshot closingSnapshot = snapshot();
        List<Listener> closingListeners = new ArrayList<>(listeners);
        listeners.clear();
        publish(closingListeners, closingSnapshot);
    }

    private void runStart() {
        TextMessageWorkerTransport transport = null;
        boolean installed = false;
        try {
            publish();
            if (abortStartIfRequested()) {
                return;
            }

            PreparedWorker prepared = Objects.requireNonNull(
                    preparation.prepare(),
                    "preparation returned null"
            );
            if (abortStartIfRequested()) {
                return;
            }

            transport = transportFactory.create(
                    prepared,
                    this::transportTerminated
            );

            installed = installTransport(prepared, transport);
            if (!installed) {
                return;
            }

            transport.start();
            publish();
        } catch (Throwable error) {
            if (installed) {
                transportTerminated(transport, error);
            } else {
                failStart(error);
            }
            rethrowError(error);
            return;
        } finally {
            if (!installed) {
                closeQuietly(transport);
            }
        }
    }

    private boolean installTransport(
            PreparedWorker prepared,
            TextMessageWorkerTransport transport
    ) {
        boolean stopped = false;
        synchronized (lock) {
            if (phase == Phase.STARTING) {
                activeTransport = transport;
                preparedWorker = prepared;
                phase = Phase.ACTIVE;
                return true;
            } else if (phase == Phase.STOPPING) {
                transitionStoppedLocked(null);
                stopped = true;
            }
        }
        if (stopped) {
            publish();
        }
        return false;
    }

    private void failStart(Throwable error) {
        boolean stopped = false;
        synchronized (lock) {
            if (phase == Phase.STARTING) {
                transitionStoppedLocked(
                        "Worker start failed: "
                                + safeFailureType(error)
                );
                stopped = true;
            } else if (phase == Phase.STOPPING) {
                transitionStoppedLocked(null);
                stopped = true;
            }
        }
        if (stopped) {
            publish();
        }
    }

    private boolean abortStartIfRequested() {
        boolean stopped = false;
        synchronized (lock) {
            if (phase == Phase.STARTING) {
                return false;
            }
            if (phase == Phase.STOPPING) {
                transitionStoppedLocked(null);
                stopped = true;
            }
        }
        if (stopped) {
            publish();
        }
        return true;
    }

    private void transportTerminated(
            TextMessageWorkerTransport transport,
            Throwable failure
    ) {
        boolean current;
        synchronized (lock) {
            current = activeTransport == transport
                    && (phase == Phase.ACTIVE
                    || phase == Phase.STOPPING);
            if (current) {
                boolean requestedStop = phase == Phase.STOPPING;
                activeTransport = null;
                transitionStoppedLocked(
                        requestedStop
                                ? null
                                : failure == null
                                        ? "Endpoint terminated"
                                        : "Worker transport failed: "
                                                + safeFailureType(failure)
                );
            }
        }
        if (current) {
            closeQuietly(transport);
            publish();
        }
    }

    private void transitionStoppedLocked(String message) {
        phase = Phase.STOPPED;
        preparedWorker = null;
        diagnosticMessage = message;
    }

    private State lifecycleStateLocked() {
        return phase == Phase.STOPPED || phase == Phase.CLOSED
                ? State.STOPPED
                : State.RUNNING;
    }

    private void publish() {
        publish(listeners, snapshot());
    }

    private static void publish(
            Iterable<Listener> currentListeners,
            Snapshot snapshot
    ) {
        for (Listener listener : currentListeners) {
            notifyListener(listener, snapshot);
        }
    }

    private static void notifyListener(
            Listener listener,
            Snapshot snapshot
    ) {
        try {
            listener.onSnapshot(snapshot);
        } catch (RuntimeException ignored) {
            // Host observers cannot interrupt Worker lifecycle.
        }
    }

    private static String safeFailureType(Throwable error) {
        String name = error == null
                ? null
                : error.getClass().getSimpleName();
        return name == null || name.isEmpty() ? "Throwable" : name;
    }

    private static void rethrowError(Throwable error) {
        if (error instanceof Error) {
            throw (Error) error;
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Lifecycle teardown is best-effort at this local boundary.
        }
    }
}
