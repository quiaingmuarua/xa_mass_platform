package com.xa.mass.worker.runtime;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
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
    private final WorkerCommandDispatcher dispatcher;
    private final NetworkClientFactory networkClientFactory;
    private final WorkerRetryPolicy retryPolicy;
    private final ScheduledExecutorService supervisor;
    private final ExecutorService commandExecutor;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private volatile Thread supervisorThread;

    private boolean commandInFlight;
    private boolean prepareAfterCommand;
    private long generation;
    private State state = State.STOPPED;
    private PreparedWorker preparedWorker;
    private TextMessageWorkerRuntime activeRuntime;
    private WorkerResultSlot pendingResult;
    private String diagnosticMessage;

    public WorkerLoop(
            WorkerPreparation preparation,
            Collection<? extends WorkerEventDefinition<?>> definitions,
            NetworkClientFactory networkClientFactory,
            WorkerRetryPolicy retryPolicy
    ) {
        this.preparation = Objects.requireNonNull(
                preparation,
                "preparation"
        );
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException(
                    "definitions must not be empty"
            );
        }
        this.dispatcher = new WorkerCommandDispatcher(definitions);
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
        commandExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "xa-worker-command"
            );
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void start() {
        long currentGeneration;
        boolean prepareNow;
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
            prepareNow = !commandInFlight;
            prepareAfterCommand = !prepareNow;
        }
        closeQuietly(oldSlot);
        publish();
        if (prepareNow) {
            executeSupervisor(
                    () -> attemptPreparation(currentGeneration, 1),
                    currentGeneration
            );
        }
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
            prepareAfterCommand = false;
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
        long commandGeneration;
        synchronized (lock) {
            if (state != State.RUNNING
                    || activeRuntime == null
                    || !activeRuntime.isConnected()
                    || commandInFlight
                    || pendingResult == null
                    || pendingResult.hasResult()) {
                return false;
            }
            commandInFlight = true;
            commandGeneration = generation;
        }
        try {
            commandExecutor.execute(
                    () -> executeCommand(commandGeneration, command)
            );
            return true;
        } catch (RejectedExecutionException error) {
            synchronized (lock) {
                commandInFlight = false;
            }
            return false;
        }
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
            prepareAfterCommand = false;
            state = State.CLOSED;
            diagnosticMessage = null;
        }
        closeQuietly(runtime);
        closeQuietly(resultSlot);
        closeQuietly(preparation);
        publishFinalAndShutdown();
        commandExecutor.shutdownNow();
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
                this::send,
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
            state = State.RUNNING;
            diagnosticMessage = null;
        }
        publish();
        try {
            runtime.start();
        } catch (RuntimeException error) {
            runtimeStartFailed(
                    currentGeneration,
                    runtime,
                    error
            );
        }
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
            if (!isCurrentRuntimeLocked(
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
                diagnosticMessage = "Text-message connection failed: "
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
        boolean prepareNow;
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
            prepareNow = !commandInFlight;
            prepareAfterCommand = !prepareNow;
        }
        closeQuietly(runtime);
        publish();
        if (prepareNow) {
            attemptPreparation(currentGeneration, 1);
        }
    }

    private void executeCommand(
            long commandGeneration,
            WorkerCommand command
    ) {
        Optional<WorkerResult> result;
        RuntimeException failure = null;
        try {
            result = dispatcher.execute(command);
        } catch (RuntimeException error) {
            result = Optional.empty();
            failure = error;
        }
        finishCommand(commandGeneration, result, failure);
    }

    private void finishCommand(
            long commandGeneration,
            Optional<WorkerResult> result,
            RuntimeException failure
    ) {
        TextMessageWorkerRuntime runtime = null;
        boolean prepareNow = false;
        long prepareGeneration = 0L;
        synchronized (lock) {
            commandInFlight = false;
            if (commandGeneration == generation
                    && (state == State.RUNNING
                            || state == State.PREPARING)) {
                if (failure != null) {
                    diagnosticMessage = "Worker command failed: "
                            + safeMessage(failure);
                } else if (result.isPresent()
                        && pendingResult != null
                        && !pendingResult.offer(result.get())) {
                    diagnosticMessage = "Worker result slot is occupied";
                }
                if (state == State.RUNNING) {
                    runtime = activeRuntime;
                }
            }
            if (state == State.PREPARING
                    && activeRuntime == null
                    && prepareAfterCommand) {
                prepareAfterCommand = false;
                prepareNow = true;
                prepareGeneration = generation;
            }
        }
        if (runtime != null) {
            runtime.flushPendingResult();
        }
        if (failure != null) {
            publish();
        }
        if (prepareNow) {
            long currentGeneration = prepareGeneration;
            executeSupervisor(
                    () -> attemptPreparation(currentGeneration, 1),
                    currentGeneration
            );
        }
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
            prepareAfterCommand = false;
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
