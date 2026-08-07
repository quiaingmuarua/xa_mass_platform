package com.xa.mass.worker.runtime;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Long-lived Worker identity loop: prepare, run, and prepare again on exit.
 */
public final class WorkerLoop implements WorkerLifecycle {

    @FunctionalInterface
    public interface NetworkClientFactory {

        TextMessageClient create(URI endpointUri);
    }

    private final Object lock = new Object();
    private final WorkerPreparation preparation;
    private final WorkerCommandExecutor commandExecutor;
    private final NetworkClientFactory networkClientFactory;
    private final WorkerRetryPolicy retryPolicy;
    private final ScheduledExecutorService supervisor;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private volatile Thread supervisorThread;

    private long generation;
    private State state = State.STOPPED;
    private PreparedWorker preparedWorker;
    private TextMessageWorkerRuntime activeRuntime;
    private WorkerResultSlot pendingResult;
    private String diagnosticMessage;

    public WorkerLoop(
            WorkerPreparation preparation,
            WorkerCommandExecutor commandExecutor,
            NetworkClientFactory networkClientFactory,
            WorkerRetryPolicy retryPolicy
    ) {
        this.preparation = Objects.requireNonNull(
                preparation,
                "preparation"
        );
        this.commandExecutor = Objects.requireNonNull(
                commandExecutor,
                "commandExecutor"
        );
        this.networkClientFactory = Objects.requireNonNull(
                networkClientFactory,
                "networkClientFactory"
        );
        this.retryPolicy = Objects.requireNonNull(
                retryPolicy,
                "retryPolicy"
        );
        supervisor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "xa-worker-supervisor"
            );
            thread.setDaemon(true);
            supervisorThread = thread;
            return thread;
        });
    }

    @Override
    public void start() {
        long currentGeneration;
        WorkerResultSlot oldSlot;
        synchronized (lock) {
            if (state == State.CLOSED) {
                throw new IllegalStateException("WorkerLoop is closed");
            }
            if (state == State.PREPARING || state == State.RUNNING) {
                return;
            }
            currentGeneration = ++generation;
            state = State.PREPARING;
            preparedWorker = null;
            diagnosticMessage = null;
            activeRuntime = null;
            oldSlot = pendingResult;
            pendingResult = new WorkerResultSlot();
        }
        closeQuietly(oldSlot);
        publish();
        executeSupervisor(
                () -> attemptPreparation(currentGeneration, 1),
                currentGeneration
        );
    }

    @Override
    public void stop() {
        TextMessageWorkerRuntime runtime;
        WorkerResultSlot resultSlot;
        synchronized (lock) {
            if (state == State.CLOSED || state == State.STOPPED) {
                return;
            }
            generation++;
            runtime = activeRuntime;
            resultSlot = pendingResult;
            activeRuntime = null;
            pendingResult = null;
            preparedWorker = null;
            state = State.STOPPED;
            diagnosticMessage = null;
        }
        closeQuietly(runtime);
        closeQuietly(resultSlot);
        publish();
    }

    @Override
    public boolean send(WorkerCommand command) {
        Objects.requireNonNull(command, "command");
        TextMessageWorkerRuntime runtime;
        synchronized (lock) {
            if (state != State.RUNNING) {
                return false;
            }
            runtime = activeRuntime;
            if (runtime == null) {
                throw new IllegalStateException(
                        "RUNNING Worker has no active runtime"
                );
            }
        }
        return runtime.send(command);
    }

    @Override
    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(
                    state,
                    connectionStateLocked(),
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
    public boolean isConnected() {
        synchronized (lock) {
            return state == State.RUNNING
                    && activeRuntime != null
                    && activeRuntime.isConnected();
        }
    }

    @Override
    public void addListener(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lock) {
            if (state == State.CLOSED) {
                throw new IllegalStateException("WorkerLoop is closed");
            }
            listeners.add(listener);
        }
        publishTo(listener);
    }

    @Override
    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    @Override
    public void close() {
        TextMessageWorkerRuntime runtime;
        WorkerResultSlot resultSlot;
        synchronized (lock) {
            if (state == State.CLOSED) {
                return;
            }
            generation++;
            runtime = activeRuntime;
            resultSlot = pendingResult;
            activeRuntime = null;
            pendingResult = null;
            preparedWorker = null;
            state = State.CLOSED;
            diagnosticMessage = null;
        }
        closeQuietly(runtime);
        closeQuietly(resultSlot);
        closeQuietly(preparation);
        publishFinalAndShutdown();
    }

    private void attemptPreparation(long currentGeneration, int attempt) {
        if (!isPreparing(currentGeneration)) {
            return;
        }
        PreparedWorker prepared;
        try {
            prepared = preparation.prepare();
        } catch (Exception error) {
            handlePreparationFailure(currentGeneration, attempt, error);
            return;
        }
        installRuntime(currentGeneration, prepared);
    }

    private void installRuntime(
            long currentGeneration,
            PreparedWorker prepared
    ) {
        TextMessageClient client;
        try {
            client = networkClientFactory.create(prepared.endpointUri());
            if (client == null) {
                throw new IllegalStateException(
                        "networkClientFactory returned null"
                );
            }
        } catch (Exception error) {
            fail(currentGeneration, safeMessage(error));
            return;
        }

        WorkerResultSlot resultSlot;
        synchronized (lock) {
            if (!isPreparingLocked(currentGeneration)) {
                closeQuietly(client);
                return;
            }
            resultSlot = pendingResult;
        }
        TextMessageWorkerRuntime runtime = new TextMessageWorkerRuntime(
                client,
                prepared.workerId(),
                commandExecutor,
                resultSlot,
                runtimeListener(currentGeneration)
        );

        synchronized (lock) {
            if (!isPreparingLocked(currentGeneration)
                    || activeRuntime != null) {
                closeQuietly(runtime);
                return;
            }
            activeRuntime = runtime;
            preparedWorker = prepared;
        }
        try {
            runtime.start();
        } catch (RuntimeException error) {
            runtimeStartFailed(
                    currentGeneration,
                    runtime,
                    error
            );
            return;
        }
        synchronized (lock) {
            if (!isInstallingRuntimeLocked(
                    currentGeneration,
                    runtime
            )) {
                closeQuietly(runtime);
                return;
            }
            state = State.RUNNING;
            diagnosticMessage = null;
        }
        publish();
    }

    private TextMessageWorkerRuntime.Listener runtimeListener(
            long currentGeneration
    ) {
        return new TextMessageWorkerRuntime.Listener() {
            @Override
            public void onStateChanged(
                    TextMessageWorkerRuntime runtime,
                    Throwable failure
            ) {
                executeSupervisor(
                        () -> runtimeStateChanged(
                                currentGeneration,
                                runtime,
                                failure
                        ),
                        currentGeneration
                );
            }

            @Override
            public void onExit(TextMessageWorkerRuntime runtime) {
                executeSupervisor(
                        () -> runtimeExited(
                                currentGeneration,
                                runtime
                        ),
                        currentGeneration
                );
            }
        };
    }

    private void runtimeStartFailed(
            long currentGeneration,
            TextMessageWorkerRuntime runtime,
            RuntimeException error
    ) {
        synchronized (lock) {
            if (!isInstallingRuntimeLocked(
                    currentGeneration,
                    runtime
            )) {
                closeQuietly(runtime);
                return;
            }
        }
        fail(currentGeneration, safeMessage(error));
    }

    private void runtimeStateChanged(
            long currentGeneration,
            TextMessageWorkerRuntime runtime,
            Throwable failure
    ) {
        synchronized (lock) {
            if (!isCurrentRuntimeLocked(
                    currentGeneration,
                    runtime
            )) {
                return;
            }
            if (failure != null) {
                diagnosticMessage = "Worker runtime reported failure: "
                        + safeMessage(failure);
            } else if (runtime.isConnected()) {
                diagnosticMessage = null;
            }
        }
        publish();
    }

    private void runtimeExited(
            long currentGeneration,
            TextMessageWorkerRuntime runtime
    ) {
        synchronized (lock) {
            if (!isCurrentRuntimeLocked(
                    currentGeneration,
                    runtime
            )) {
                return;
            }
            activeRuntime = null;
            preparedWorker = null;
            state = State.PREPARING;
            diagnosticMessage = "Connection retry budget exhausted; "
                    + "preparing Worker again";
        }
        closeQuietly(runtime);
        publish();
        attemptPreparation(currentGeneration, 1);
    }

    private void handlePreparationFailure(
            long currentGeneration,
            int attempt,
            Exception error
    ) {
        String message = "Worker preparation failed: " + safeMessage(error);
        if (!isRetryablePreparationFailure(error)
                || attempt >= retryPolicy.maxPrepareAttempts()) {
            fail(currentGeneration, message);
            return;
        }
        synchronized (lock) {
            if (!isPreparingLocked(currentGeneration)) {
                return;
            }
            diagnosticMessage = message;
        }
        publish();
        try {
            supervisor.schedule(
                    () -> attemptPreparation(
                            currentGeneration,
                            attempt + 1
                    ),
                    retryPolicy.prepareRetryInterval().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException ignored) {
            // close owns cancellation of stale preparation retries.
        }
    }

    private void fail(long currentGeneration, String message) {
        TextMessageWorkerRuntime runtime;
        WorkerResultSlot resultSlot;
        synchronized (lock) {
            if (generation != currentGeneration
                    || (state != State.PREPARING
                            && state != State.RUNNING)) {
                return;
            }
            runtime = activeRuntime;
            resultSlot = pendingResult;
            activeRuntime = null;
            pendingResult = null;
            preparedWorker = null;
            state = State.ERROR;
            diagnosticMessage = message;
        }
        closeQuietly(runtime);
        closeQuietly(resultSlot);
        publish();
    }

    private boolean isPreparing(long currentGeneration) {
        synchronized (lock) {
            return isPreparingLocked(currentGeneration);
        }
    }

    private boolean isPreparingLocked(long currentGeneration) {
        return generation == currentGeneration
                && state == State.PREPARING
                && activeRuntime == null;
    }

    private boolean isCurrentRuntimeLocked(
            long currentGeneration,
            TextMessageWorkerRuntime runtime
    ) {
        return generation == currentGeneration
                && state == State.RUNNING
                && activeRuntime == runtime;
    }

    private boolean isInstallingRuntimeLocked(
            long currentGeneration,
            TextMessageWorkerRuntime runtime
    ) {
        return generation == currentGeneration
                && state == State.PREPARING
                && activeRuntime == runtime;
    }

    private ConnectionState connectionStateLocked() {
        if (activeRuntime == null) {
            return ConnectionState.DISCONNECTED;
        }
        return activeRuntime.isConnected()
                ? ConnectionState.CONNECTED
                : ConnectionState.CONNECTING;
    }

    private void executeSupervisor(
            Runnable runnable,
            long currentGeneration
    ) {
        try {
            supervisor.execute(runnable);
        } catch (RejectedExecutionException error) {
            fail(currentGeneration, "Worker supervisor is unavailable");
        }
    }

    private void publish() {
        Snapshot current = snapshot();
        if (Thread.currentThread() == supervisorThread) {
            publishNow(current);
            return;
        }
        try {
            supervisor.execute(() -> publishNow(current));
        } catch (RejectedExecutionException ignored) {
            // Terminal close may reject a stale notification.
        }
    }

    private void publishTo(Listener listener) {
        try {
            supervisor.execute(() -> {
                if (listeners.contains(listener)) {
                    notifyListener(listener, snapshot());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // A listener added during close receives no callback.
        }
    }

    private void publishNow(Snapshot current) {
        for (Listener listener : listeners) {
            notifyListener(listener, current);
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

    private void publishFinalAndShutdown() {
        Snapshot current = snapshot();
        if (Thread.currentThread() == supervisorThread) {
            publishNow(current);
            listeners.clear();
            supervisor.shutdownNow();
            return;
        }
        try {
            supervisor.execute(() -> {
                publishNow(current);
                listeners.clear();
            });
        } catch (RejectedExecutionException ignored) {
            listeners.clear();
        } finally {
            supervisor.shutdown();
        }
    }

    private static boolean isRetryablePreparationFailure(Exception error) {
        if (error instanceof IOException) {
            return true;
        }
        if (error instanceof WorkerException) {
            return ((WorkerException) error).errorCode()
                    == WorkerErrorCode.WORKER_CONTROL_UNAVAILABLE;
        }
        return false;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "Unknown failure";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
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
