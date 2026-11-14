package com.xa.mass.worker.runtime;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.error.WorkerErrorCode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private StopReason stopReason;
    private Long cooldownUntilMillis;
    private String cooledWorkerId;

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
        requestStart(null);
    }

    /**
     * Starts one run from a coordinate prepared by the owning Host.
     */
    public void start(WorkerPreparationResult preparedWorker) {
        requestStart(Objects.requireNonNull(
                preparedWorker,
                "preparedWorker"
        ));
    }

    private void requestStart(WorkerPreparationResult suppliedPreparedWorker) {
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
            this.preparedWorker = null;
            activeTransport = null;
            diagnosticMessage = null;
            stopReason = null;
            cooldownUntilMillis = null;
            cooledWorkerId = null;
        }
        publish();
        try {
            controlExecutor.execute(
                    () -> runStart(suppliedPreparedWorker)
            );
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

    /** One-shot full observation from the Host's existing Properties provider. */
    public boolean reportProperties() {
        TextMessageWorkerTransport transport = currentTransport();
        return transport != null && transport.reportProperties();
    }

    /** Sends an incremental merge; the Host owns the corresponding data update. */
    public boolean reportProperties(Map<String, String> updates) {
        Map<String, String> captured = WorkerDeliveryCodec.copyWorkerProperties(updates);
        TextMessageWorkerTransport transport = currentTransport();
        return transport != null && transport.reportProperties(captured);
    }

    private TextMessageWorkerTransport currentTransport() {
        synchronized (stateLock) {
            return activeTransport;
        }
    }

    @Override
    public Snapshot snapshot() {
        synchronized (stateLock) {
            return new Snapshot(
                    lifecycleStateLocked(),
                    preparedWorker == null
                            ? cooledWorkerId
                            : preparedWorker.workerId(),
                    preparedWorker == null
                            ? null
                            : preparedWorker.endpointUri(),
                    diagnosticMessage,
                    stopReason,
                    cooldownUntilMillis
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
            stopReason = StopReason.MANUAL;
            cooldownUntilMillis = null;
            cooledWorkerId = null;
        }

        closeQuietly(transport);
        closeQuietly(preparation);
        Snapshot closingSnapshot = snapshot();
        List<Listener> closingListeners = new ArrayList<>(listeners);
        listeners.clear();
        publish(closingListeners, closingSnapshot);
    }

    private void runStart(WorkerPreparationResult suppliedPreparedWorker) {
        TextMessageWorkerTransport transport = null;
        boolean installed = false;
        try {
            WorkerPreparationResult result = Objects.requireNonNull(
                    suppliedPreparedWorker == null
                            ? preparation.prepare()
                            : suppliedPreparedWorker,
                    "preparation returned null"
            );
            if (finishDiscardedPreparation()) {
                return;
            }

            if (result.cooldownUntilMillis() != null) {
                boolean stopped = false;
                synchronized (stateLock) {
                    if (phase == Phase.PREPARING) {
                        if (discardPreparedResult) {
                            transitionStoppedLocked(null);
                        } else {
                            transitionStoppedLocked("Worker connection is cooling down",
                                    StopReason.COOLDOWN, result.cooldownUntilMillis());
                            cooledWorkerId = result.workerId();
                        }
                        stopped = true;
                    }
                }
                if (stopped) publish();
                return;
            }
            PreparedWorker prepared = result.preparedWorker();

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
                                        + safeFailureType(failure),
                        discardPreparedResult ? StopReason.MANUAL : StopReason.UNAVAILABLE, null
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
                                        + safeFailureType(error),
                        discardPreparedResult ? StopReason.MANUAL : failureReason(error), null
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
                Long until = transport.adapterClose() == null ? null
                        : transport.adapterClose().cooldownUntilMillis();
                String workerId = preparedWorker == null ? null : preparedWorker.workerId();
                transitionStoppedLocked(until != null ? "Worker connection is cooling down"
                                : failure == null ? "Endpoint terminated"
                                : "Worker transport failed: " + safeFailureType(failure),
                        until != null ? StopReason.COOLDOWN
                                : transport.adapterClose() != null ? StopReason.TERMINATED : StopReason.UNAVAILABLE,
                        until);
                if (until != null) cooledWorkerId = workerId;
            }
        }
        if (current) {
            closeQuietly(transport);
            publish();
        }
    }

    private void transitionStoppedLocked(String message) {
        transitionStoppedLocked(message, StopReason.MANUAL, null);
    }

    private void transitionStoppedLocked(String message, StopReason reason, Long untilMillis) {
        phase = Phase.STOPPED;
        discardPreparedResult = false;
        preparedWorker = null;
        diagnosticMessage = message;
        stopReason = reason;
        cooldownUntilMillis = untilMillis;
        cooledWorkerId = null;
    }

    private static StopReason failureReason(Throwable error) {
        if (error instanceof IOException) return StopReason.UNAVAILABLE;
        if (error instanceof WorkerException
                && ((WorkerException) error).errorCode() == WorkerErrorCode.WORKER_CONTROL_UNAVAILABLE) {
            return StopReason.UNAVAILABLE;
        }
        return StopReason.REJECTED;
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
