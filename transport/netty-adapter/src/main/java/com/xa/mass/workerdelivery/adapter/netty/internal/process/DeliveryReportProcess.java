package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.process.FiniteQueue.QueueIngressStatus.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.remote.DeliveryReportRemoteApi.MAX_RESULTS_PER_APPEND;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.DeliveryReportRemoteApi;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Resident Result ingress process for one Adapter instance. */
public final class DeliveryReportProcess implements AdapterProcess {

    private static final System.Logger LOGGER = System.getLogger(
            DeliveryReportProcess.class.getName()
    );

    private final FiniteQueue<String> reportQueue;
    private final DeliveryReportRemoteApi remoteApi;
    private final String adapterId;
    private final long retryBackoffMillis;
    private final AtomicBoolean finishStarted = new AtomicBoolean();
    private List<String> pendingBatch;
    private volatile boolean loopStopped;

    public DeliveryReportProcess(
            DeliveryReportRemoteApi remoteApi,
            String adapterId,
            int queueCapacity
    ) {
        this(
                remoteApi,
                adapterId,
                queueCapacity,
                Duration.ofSeconds(1)
        );
    }

    public DeliveryReportProcess(
            DeliveryReportRemoteApi remoteApi,
            String adapterId,
            int queueCapacity,
            Duration retryBackoff
    ) {
        this.remoteApi = Objects.requireNonNull(remoteApi, "remoteApi");
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must be non-blank");
        }
        if (queueCapacity < 2) {
            throw new IllegalArgumentException(
                    "queueCapacity must be at least 2"
            );
        }
        this.adapterId = adapterId;
        retryBackoffMillis = requirePositiveMillis(
                retryBackoff,
                "retryBackoff"
        );
        reportQueue = new FiniteQueue<>(queueCapacity);
    }

    @Override
    public void runLoop() {
        while (!loopStopped && !Thread.currentThread().isInterrupted()) {
            List<String> batch = pendingBatch;
            if (batch == null) {
                try {
                    batch = reportQueue.awaitAndConsume(
                            maximumRemoteBatchSize()
                    );
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (batch.isEmpty()) {
                    return;
                }
                pendingBatch = batch;
            }
            if (loopStopped) {
                return;
            }

            SubmissionOutcome outcome = submit(batch);
            if (outcome != SubmissionOutcome.RETRY) {
                pendingBatch = null;
                continue;
            }
            if (!awaitRetryBackoff()) {
                return;
            }
        }
    }

    @Override
    public void quiesce() {
        loopStopped = true;
        reportQueue.stopIngress();
    }

    @Override
    public void finishAfterLoopStop() {
        if (!finishStarted.compareAndSet(false, true)) {
            return;
        }
        quiesce();

        if (pendingBatch != null) {
            SubmissionOutcome outcome = submit(pendingBatch);
            if (outcome != SubmissionOutcome.RETRY) {
                pendingBatch = null;
            }
        }
        if (pendingBatch == null) {
            List<String> remaining = reportQueue.consume(
                    maximumRemoteBatchSize()
            );
            if (!remaining.isEmpty()
                    && submit(remaining) == SubmissionOutcome.RETRY) {
                pendingBatch = remaining;
            }
        }
        // No resident loop exists after this bounded best-effort close attempt.
        pendingBatch = null;
        reportQueue.clear();
    }

    public ReportIngressStatus ingress(List<String> encodedReports) {
        return switch (reportQueue.ingress(encodedReports)) {
            case ACCEPTED -> ReportIngressStatus.ACCEPTED;
            case FULL -> ReportIngressStatus.FULL;
            case CLOSED -> ReportIngressStatus.CLOSED;
        };
    }

    private boolean awaitRetryBackoff() {
        if (loopStopped || Thread.currentThread().isInterrupted()) {
            return false;
        }
        try {
            Thread.sleep(retryBackoffMillis);
            return !loopStopped;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private int maximumRemoteBatchSize() {
        return Math.min(reportQueue.capacity(), MAX_RESULTS_PER_APPEND);
    }

    private SubmissionOutcome submit(List<String> batch) {
        try {
            remoteApi.append(adapterId, batch);
            return SubmissionOutcome.SUCCESS;
        } catch (RuntimeException error) {
            WorkerDeliveryAdapterException failure = classify(error);
            if (!Thread.currentThread().isInterrupted()) {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "errorCode={0} operation={1} adapterId={2} "
                                + "message={3}",
                        failure.errorCode().code(),
                        failure.operation(),
                        adapterId,
                        failure.getMessage()
                );
            }
            return failure.errorCode()
                    == WorkerDeliveryAdapterErrorCode
                    .REMOTE_API_PROTOCOL_ERROR
                    ? SubmissionOutcome.DROP
                    : SubmissionOutcome.RETRY;
        }
    }

    private static long requirePositiveMillis(
            Duration value,
            String name
    ) {
        Objects.requireNonNull(value, name);
        if (value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value.toMillis();
    }

    private static WorkerDeliveryAdapterException classify(
            RuntimeException error
    ) {
        if (error instanceof WorkerDeliveryAdapterException classified) {
            return classified;
        }
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE,
                "deliveryReport.submitRemote",
                "Worker result submission failed",
                error
        );
    }

    public enum ReportIngressStatus {
        ACCEPTED,
        FULL,
        CLOSED
    }

    private enum SubmissionOutcome {
        SUCCESS,
        RETRY,
        DROP
    }
}
