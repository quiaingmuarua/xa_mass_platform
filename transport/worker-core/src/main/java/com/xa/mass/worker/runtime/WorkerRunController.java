package com.xa.mass.worker.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;

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
        PREPARING,
        ACTIVE,
        CLOSED
    }

    private final Object stateLock = new Object();
    private final WorkerPreparation preparation;
    private final TextMessageWorkerTransportFactory transportFactory;
    private final Executor controlExecutor;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();

    private Phase phase = Phase.STOPPED;
    private boolean discardPreparedResult;
    private PreparedWorker preparedWorker;
    private TextMessageWorkerTransport activeTransport;
    private String diagnosticMessage;

    public WorkerRunController(
            WorkerPreparation preparation,
            TextMessageWorkerTransportFactory transportFactory,
            Executor controlExecutor
    ) {
        this.preparation = Objects.requireNonNull(
                preparation,
                "preparation"
        );
        this.transportFactory = Objects.requireNonNull(
                transportFactory,
                "transportFactory"
        );
        this.controlExecutor = Objects.requireNonNull(
                controlExecutor,
                "controlExecutor"
        );
    }

    @Override
    public void start() {
        synchronized (stateLock) {
            if (phase == Phase.CLOSED) {
                throw new IllegalStateException(
                        "WorkerRunController is closed"
                );
            }
            if (phase != Phase.STOPPED) {
                return;
            }
            phase = Phase.PREPARING;
            discardPreparedResult = false;
            preparedWorker = null;
            activeTransport = null;
            diagnosticMessage = null;
        }
        publish();
        try {
            controlExecutor.execute(this::runStart);
        } catch (RuntimeException | Error failure) {
            rejectStart(failure);
            throw failure;
        }
    }

    @Override
    public void stop() {
        TextMessageWorkerTransport transport = null;
        boolean changed = false;
        synchronized (stateLock) {
            if (phase == Phase.PREPARING) {
                if (!discardPreparedResult) {
                    discardPreparedResult = true;
                    changed = true;
                }
            } else if (phase == Phase.ACTIVE) {
                transport = activeTransport;
                activeTransport = null;
                transitionStoppedLocked(null);
                changed = true;
            }
        }
        closeQuietly(transport);
        if (changed) {
            publish();
        }
    }

    @Override
    public Snapshot snapshot() {
        synchronized (stateLock) {
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
        synchronized (stateLock) {
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
        synchronized (stateLock) {
            if (phase == Phase.CLOSED) {
                return;
            }
            phase = Phase.CLOSED;
            discardPreparedResult = false;
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
            PreparedWorker prepared = Objects.requireNonNull(
                    preparation.prepare(),
                    "preparation returned null"
            );
            if (finishDiscardedPreparation()) {
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
        } finally {
            if (!installed) {
                closeQuietly(transport);
            }
        }
    }

    private void rejectStart(Throwable failure) {
        boolean stopped = false;
        synchronized (stateLock) {
            if (phase == Phase.PREPARING) {
                transitionStoppedLocked(
                        discardPreparedResult
                                ? null
                                : "Worker start request rejected: "
                                        + safeFailureType(failure)
                );
                stopped = true;
            }
        }
        if (stopped) {
            publish();
        }
    }

    private boolean installTransport(
            PreparedWorker prepared,
            TextMessageWorkerTransport transport
    ) {
        boolean stopped = false;
        synchronized (stateLock) {
            if (phase == Phase.PREPARING
                    && !discardPreparedResult) {
                activeTransport = transport;
                preparedWorker = prepared;
                phase = Phase.ACTIVE;
                return true;
            }
            if (phase == Phase.PREPARING) {
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
        synchronized (stateLock) {
            if (phase == Phase.PREPARING) {
                transitionStoppedLocked(
                        discardPreparedResult
                                ? null
                                : "Worker start failed: "
                                        + safeFailureType(error)
                );
                stopped = true;
            }
        }
        if (stopped) {
            publish();
        }
    }

    private boolean finishDiscardedPreparation() {
        boolean stopped = false;
        synchronized (stateLock) {
            if (phase == Phase.PREPARING
                    && !discardPreparedResult) {
                return false;
            }
            if (phase == Phase.PREPARING) {
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
        synchronized (stateLock) {
            current = phase == Phase.ACTIVE
                    && activeTransport == transport;
            if (current) {
                activeTransport = null;
                transitionStoppedLocked(
                        failure == null
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
        discardPreparedResult = false;
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
