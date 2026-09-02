package com.xa.mass.server.delivery.adapter;

import com.xa.mass.server.worker.binding.WorkerBindingService;
import com.xa.mass.workerdelivery.adapter.application.WorkerRouteVerifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Batches transient Adapter route checks into bounded Binding owner reads. */
public final class WorkerRouteVerificationBatcher
        implements WorkerRouteVerifier, AutoCloseable {

    static final int MAX_BATCH_SIZE = 100;

    private final WorkerBindingService bindings;
    private final Duration timeout;
    private final ArrayBlockingQueue<Request> requests;
    private final Object lifecycleGate = new Object();

    private State state = State.NEW;
    private Thread workerThread;
    private List<Request> activeBatch = List.of();
    private CompletableFuture<?> activeLookup;

    public WorkerRouteVerificationBatcher(
            WorkerBindingService bindings,
            int queueCapacity,
            Duration timeout
    ) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "queueCapacity must be positive"
            );
        }
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        requests = new ArrayBlockingQueue<>(queueCapacity);
    }

    public void start() {
        Thread thread;
        synchronized (lifecycleGate) {
            if (state == State.RUNNING) {
                return;
            }
            if (state != State.NEW) {
                throw new IllegalStateException(
                        "Worker route verification batcher is closed"
                );
            }
            thread = Thread.ofVirtual()
                    .name("worker-route-verification")
                    .unstarted(this::run);
            workerThread = thread;
            state = State.RUNNING;
        }
        try {
            thread.start();
        } catch (RuntimeException | Error failure) {
            synchronized (lifecycleGate) {
                state = State.CLOSED;
                workerThread = null;
            }
            throw failure;
        }
    }

    @Override
    public CompletableFuture<Decision> verify(
            String adapterId,
            String workerId
    ) {
        Request request = new Request(
                requireNonBlank(adapterId, "adapterId"),
                requireNonBlank(workerId, "workerId"),
                new CompletableFuture<>()
        );
        synchronized (lifecycleGate) {
            if (state != State.RUNNING) {
                return failed("Worker route verification is not running");
            }
            if (!requests.offer(request)) {
                return failed("Worker route verification queue is full");
            }
        }
        return request.completion();
    }

    public void stopIngress() {
        List<Request> rejected = new ArrayList<>();
        CompletableFuture<?> lookup;
        Thread thread;
        synchronized (lifecycleGate) {
            if (state == State.STOPPING || state == State.CLOSED) {
                return;
            }
            state = State.STOPPING;
            rejected.addAll(activeBatch);
            activeBatch = List.of();
            requests.drainTo(rejected);
            lookup = activeLookup;
            activeLookup = null;
            thread = workerThread;
        }
        fail(rejected, new IllegalStateException(
                "Worker route verification is stopping"
        ));
        if (lookup != null) {
            lookup.cancel(true);
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void close() {
        stopIngress();
        Thread thread;
        synchronized (lifecycleGate) {
            if (state == State.CLOSED) {
                return;
            }
            thread = workerThread;
        }
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(timeout);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while closing Worker route verification",
                        error
                );
            }
            if (thread.isAlive()) {
                throw new IllegalStateException(
                        "Worker route verification thread did not stop"
                );
            }
        }
        synchronized (lifecycleGate) {
            state = State.CLOSED;
            workerThread = null;
        }
    }

    private void run() {
        while (isRunning()) {
            List<Request> batch;
            try {
                batch = takeBatch();
            } catch (InterruptedException error) {
                if (!isRunning()) {
                    return;
                }
                continue;
            }
            if (batch == null) {
                return;
            }
            process(batch);
        }
    }

    private List<Request> takeBatch() throws InterruptedException {
        Request first = requests.take();
        ArrayList<Request> batch = new ArrayList<>(MAX_BATCH_SIZE);
        batch.add(first);
        synchronized (lifecycleGate) {
            if (state != State.RUNNING) {
                first.completion().completeExceptionally(
                        new IllegalStateException(
                                "Worker route verification is stopping"
                        )
                );
                return null;
            }
            requests.drainTo(batch, MAX_BATCH_SIZE - 1);
            activeBatch = batch;
        }
        return batch;
    }

    private void process(List<Request> batch) {
        CompletableFuture<Map<String, String>> lookup;
        try {
            List<String> workerIds = List.copyOf(new LinkedHashSet<>(
                    batch.stream().map(Request::workerId).toList()
            ));
            lookup = bindings.currentEndpointManagerIdsAsync(workerIds)
                    .toCompletableFuture();
            synchronized (lifecycleGate) {
                if (state != State.RUNNING || activeBatch != batch) {
                    lookup.cancel(true);
                    return;
                }
                activeLookup = lookup;
            }
            Map<String, String> endpoints = lookup.get(
                    timeout.toNanos(),
                    TimeUnit.NANOSECONDS
            );
            if (!releaseActive(batch)) {
                return;
            }
            batch.forEach(request -> request.completion().complete(
                    request.adapterId().equals(
                            endpoints.get(request.workerId())
                    ) ? Decision.VERIFIED : Decision.REJECTED
            ));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failBatchAndQueued(batch, error);
        } catch (ExecutionException error) {
            failBatchAndQueued(batch, error.getCause());
        } catch (TimeoutException error) {
            cancelActiveLookup(batch);
            failBatchAndQueued(batch, error);
        } catch (RuntimeException error) {
            failBatchAndQueued(batch, error);
        } catch (Error error) {
            failBatchAndQueued(batch, error);
            throw error;
        }
    }

    private boolean releaseActive(List<Request> batch) {
        synchronized (lifecycleGate) {
            if (state != State.RUNNING || activeBatch != batch) {
                return false;
            }
            activeBatch = List.of();
            activeLookup = null;
            return true;
        }
    }

    private void cancelActiveLookup(List<Request> batch) {
        CompletableFuture<?> lookup = null;
        synchronized (lifecycleGate) {
            if (activeBatch == batch) {
                lookup = activeLookup;
            }
        }
        if (lookup != null) {
            lookup.cancel(true);
        }
    }

    private void failBatchAndQueued(
            List<Request> batch,
            Throwable failure
    ) {
        ArrayList<Request> failed = new ArrayList<>(batch);
        synchronized (lifecycleGate) {
            if (activeBatch == batch) {
                activeBatch = List.of();
                activeLookup = null;
            }
            requests.drainTo(failed);
        }
        fail(failed, Objects.requireNonNull(failure, "failure"));
    }

    private boolean isRunning() {
        synchronized (lifecycleGate) {
            return state == State.RUNNING;
        }
    }

    private static void fail(List<Request> batch, Throwable failure) {
        batch.forEach(request ->
                request.completion().completeExceptionally(failure)
        );
    }

    private static CompletableFuture<Decision> failed(String message) {
        return CompletableFuture.failedFuture(
                new IllegalStateException(message)
        );
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }

    private record Request(
            String adapterId,
            String workerId,
            CompletableFuture<Decision> completion
    ) {
    }

    private enum State {
        NEW,
        RUNNING,
        STOPPING,
        CLOSED
    }
}
