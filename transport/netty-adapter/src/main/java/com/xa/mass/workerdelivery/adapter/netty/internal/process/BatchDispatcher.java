package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;

/** One finite Adapter queue, consumer loop, and same-lifetime thread. */
public final class BatchDispatcher<T> {

    private static final System.Logger LOGGER = System.getLogger(
            BatchDispatcher.class.getName()
    );

    private final Object admissionGate = new Object();
    private final LinkedBlockingQueue<T> queue;
    private final int softCapacity;
    private final int batchSize;
    private final long backoffMillis;
    private final AdapterBatchProcessor<T> processor;
    private final Supplier<List<T>> freshSource;
    private final String adapterId;
    private final String dispatcherId;
    private final Thread thread;
    private boolean accepting = true;
    private volatile boolean stopped;

    private BatchDispatcher(
            String adapterId,
            String dispatcherId,
            int softCapacity,
            int batchSize,
            Duration backoff,
            Supplier<List<T>> freshSource,
            AdapterBatchProcessor<T> processor
    ) {
        this.adapterId = requireNonBlank(adapterId, "adapterId");
        this.dispatcherId = requireNonBlank(
                dispatcherId,
                "dispatcherId"
        );
        if (softCapacity <= 0) {
            throw new IllegalArgumentException(
                    "softCapacity must be positive"
            );
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        long physicalCapacity = 2L * softCapacity - 1L;
        if (physicalCapacity > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("softCapacity is too large");
        }
        this.softCapacity = softCapacity;
        this.batchSize = batchSize;
        backoffMillis = requirePositiveMillis(backoff, "backoff");
        this.freshSource = freshSource;
        this.processor = Objects.requireNonNull(processor, "processor");
        queue = new LinkedBlockingQueue<>((int) physicalCapacity);
        thread = new Thread(
                this::runLoop,
                "worker-delivery-" + adapterId + "-" + dispatcherId
        );
        thread.setDaemon(true);
    }

    public static <T> BatchDispatcher<T> pulling(
            String adapterId,
            String dispatcherId,
            int softCapacity,
            int batchSize,
            Duration backoff,
            Supplier<List<T>> freshSource,
            AdapterBatchProcessor<T> processor
    ) {
        return new BatchDispatcher<>(
                adapterId,
                dispatcherId,
                softCapacity,
                batchSize,
                backoff,
                Objects.requireNonNull(freshSource, "freshSource"),
                processor
        );
    }

    public static <T> BatchDispatcher<T> queued(
            String adapterId,
            String dispatcherId,
            int softCapacity,
            int batchSize,
            Duration backoff,
            AdapterBatchProcessor<T> processor
    ) {
        return new BatchDispatcher<>(
                adapterId,
                dispatcherId,
                softCapacity,
                batchSize,
                backoff,
                null,
                processor
        );
    }

    public DispatchStatus tryDispatch(List<T> items) {
        return admit(items, false);
    }

    void start() {
        thread.start();
    }

    void stopIngress() {
        synchronized (admissionGate) {
            accepting = false;
        }
    }

    void stop() {
        stopped = true;
        thread.interrupt();
    }

    boolean isAlive() {
        return thread.isAlive();
    }

    Thread thread() {
        return thread;
    }

    void join(long remainingNanos) throws InterruptedException {
        if (remainingNanos <= 0) {
            return;
        }
        long millis = remainingNanos / 1_000_000L;
        int nanos = (int) (remainingNanos % 1_000_000L);
        thread.join(millis, nanos);
    }

    private void runLoop() {
        try {
            if (freshSource == null) {
                runQueuedLoop();
            } else {
                runPullingLoop();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            stopIngress();
            queue.clear();
        }
    }

    private void runQueuedLoop() throws InterruptedException {
        while (isActive()) {
            List<T> batch = takeBatch();
            if (!isActive()) {
                return;
            }
            BatchAttempt attempt = processOnce(batch, true);
            if (attempt == BatchAttempt.STOPPED) {
                return;
            }
            if (attempt == BatchAttempt.FAILED) {
                awaitBackoff();
            }
        }
    }

    private void runPullingLoop() throws InterruptedException {
        while (isActive()) {
            boolean failed = false;
            List<T> retryBatch = drainBatch();
            if (!retryBatch.isEmpty()) {
                BatchAttempt retryAttempt = processOnce(
                        retryBatch,
                        false
                );
                if (retryAttempt == BatchAttempt.STOPPED) {
                    return;
                }
                failed = retryAttempt == BatchAttempt.FAILED;
            }
            if (!isActive()) {
                return;
            }

            boolean fresh = false;
            try {
                List<T> freshBatch = copyFreshBatch(freshSource.get());
                fresh = !freshBatch.isEmpty();
                if (fresh) {
                    BatchAttempt freshAttempt = processOnce(
                            freshBatch,
                            false
                    );
                    if (freshAttempt == BatchAttempt.STOPPED) {
                        return;
                    }
                    failed = failed
                            || freshAttempt == BatchAttempt.FAILED;
                }
            } catch (RuntimeException error) {
                if (!isActive()) {
                    return;
                }
                logFailure(normalize(
                        error,
                        "batchDispatcher.loadFresh",
                        "Adapter fresh batch acquisition failed"
                ));
                failed = true;
            }

            if (!fresh || failed) {
                awaitBackoff();
            }
        }
    }

    private BatchAttempt processOnce(
            List<T> batch,
            boolean requeueUnavailable
    ) {
        try {
            BatchProcessResult result = Objects.requireNonNull(
                    processor.process(batch),
                    "processor result"
            );
            if (!isActive()) {
                return BatchAttempt.STOPPED;
            }
            if (result.isCompleted()) {
                return BatchAttempt.COMPLETED;
            }
            if (result.errorCode()
                    != WorkerDeliveryAdapterErrorCode
                    .WORKER_DELIVERY_RETRY_LATER) {
                logFailure(new WorkerDeliveryAdapterException(
                        result.errorCode(),
                        "batchDispatcher.processResult",
                        "Unsupported Adapter batch result",
                        null
                ));
                return BatchAttempt.FAILED;
            }
            List<T> retryItems = selectItems(
                    batch,
                    result.requeueIndexes()
            );
            logDropIfRejected(
                    admit(retryItems, true),
                    result.errorCode(),
                    retryItems.size()
            );
            return BatchAttempt.REQUEUED;
        } catch (RuntimeException error) {
            if (!isActive()) {
                return BatchAttempt.STOPPED;
            }
            WorkerDeliveryAdapterException failure = normalize(
                    error,
                    "batchDispatcher.process",
                    "Adapter batch processing failed"
            );
            if (requeueUnavailable
                    && failure.errorCode()
                    == WorkerDeliveryAdapterErrorCode
                    .REMOTE_API_UNAVAILABLE) {
                logDropIfRejected(
                        admit(batch, true),
                        failure.errorCode(),
                        batch.size()
                );
            }
            logFailure(failure);
            return BatchAttempt.FAILED;
        }
    }

    private List<T> copyFreshBatch(List<T> batch) {
        List<T> copied = List.copyOf(
                Objects.requireNonNull(batch, "fresh batch")
        );
        if (copied.size() > batchSize) {
            throw new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode
                            .REMOTE_API_PROTOCOL_ERROR,
                    "batchDispatcher.loadFresh",
                    "Fresh Adapter batch exceeded its requested limit",
                    null
            );
        }
        return copied;
    }

    private List<T> takeBatch() throws InterruptedException {
        ArrayList<T> batch = new ArrayList<>(batchSize);
        batch.add(queue.take());
        queue.drainTo(batch, batchSize - 1);
        return List.copyOf(batch);
    }

    private List<T> drainBatch() {
        ArrayList<T> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        return List.copyOf(batch);
    }

    private List<T> selectItems(
            List<T> batch,
            List<Integer> indexes
    ) {
        ArrayList<T> selected = new ArrayList<>(indexes.size());
        for (int index : indexes) {
            if (index >= batch.size()) {
                throw new IllegalArgumentException(
                        "requeue index must be inside the current batch"
                );
            }
            selected.add(batch.get(index));
        }
        return List.copyOf(selected);
    }

    private DispatchStatus admit(List<T> offered, boolean requeue) {
        Objects.requireNonNull(offered, "offered");
        List<T> batch = List.copyOf(offered);
        if (!requeue && batch.size() > softCapacity) {
            throw new IllegalArgumentException(
                    "dispatch batch must not exceed softCapacity"
            );
        }
        synchronized (admissionGate) {
            if (!accepting) {
                return DispatchStatus.CLOSED;
            }
            if (batch.isEmpty()) {
                return DispatchStatus.ACCEPTED;
            }
            if (queue.size() >= softCapacity
                    || queue.remainingCapacity() < batch.size()) {
                return DispatchStatus.FULL;
            }
            queue.addAll(batch);
            return DispatchStatus.ACCEPTED;
        }
    }

    private void logDropIfRejected(
            DispatchStatus status,
            WorkerDeliveryAdapterErrorCode errorCode,
            int itemCount
    ) {
        if (status == DispatchStatus.ACCEPTED
                || status == DispatchStatus.CLOSED && stopped) {
            return;
        }
        LOGGER.log(
                System.Logger.Level.WARNING,
                "errorCode={0} adapterId={1} dispatcher={2} itemCount={3} "
                        + "message={4}",
                errorCode.code(),
                adapterId,
                dispatcherId,
                itemCount,
                "Adapter retry batch was dropped"
        );
    }

    private void logFailure(WorkerDeliveryAdapterException failure) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "errorCode={0} operation={1} adapterId={2} dispatcher={3} "
                        + "message={4}",
                failure.errorCode().code(),
                failure.operation(),
                adapterId,
                dispatcherId,
                failure.getMessage()
        );
    }

    private WorkerDeliveryAdapterException normalize(
            RuntimeException error,
            String operation,
            String message
    ) {
        if (error instanceof WorkerDeliveryAdapterException classified) {
            return classified;
        }
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.DELIVERY_INTERRUPTED,
                operation,
                message,
                error
        );
    }

    private void awaitBackoff() throws InterruptedException {
        if (isActive()) {
            Thread.sleep(backoffMillis);
        }
    }

    private boolean isActive() {
        return !stopped && !Thread.currentThread().isInterrupted();
    }

    private static long requirePositiveMillis(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value.toMillis();
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }

    public enum DispatchStatus {
        ACCEPTED,
        FULL,
        CLOSED
    }

    private enum BatchAttempt {
        COMPLETED,
        REQUEUED,
        FAILED,
        STOPPED
    }
}
