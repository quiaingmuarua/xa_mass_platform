package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.process.FiniteQueue.QueueIngressStatus.ACCEPTED;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.DeliveryReportRemoteApi;
import java.util.List;
import java.util.Objects;

/** Scheduled Result ingress process for one Adapter instance. */
public final class DeliveryReportProcess implements AdapterProcess {

    private static final System.Logger LOGGER = System.getLogger(
            DeliveryReportProcess.class.getName()
    );

    private final FiniteQueue<String> reportQueue;
    private final DeliveryReportRemoteApi remoteApi;
    private final String adapterId;
    private List<String> pendingBatch;
    private volatile boolean roundsStopped;
    private boolean closeFinished;

    public DeliveryReportProcess(
            DeliveryReportRemoteApi remoteApi,
            String adapterId,
            int queueCapacity
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
        reportQueue = new FiniteQueue<>(queueCapacity);
    }

    @Override
    public void round() {
        if (roundsStopped || closeFinished) {
            return;
        }
        submitAtMostOneBatch();
    }

    @Override
    public void quiesce() {
        roundsStopped = true;
        reportQueue.stopIngress();
    }

    @Override
    public synchronized void finishAfterSchedulerStop() {
        if (closeFinished) {
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
                    reportQueue.capacity()
            );
            if (!remaining.isEmpty()
                    && submit(remaining) == SubmissionOutcome.RETRY) {
                pendingBatch = remaining;
            }
        }
        // No scheduler round exists after this best-effort close attempt.
        pendingBatch = null;
        reportQueue.clear();
        closeFinished = true;
    }

    public ReportIngressStatus ingress(List<String> encodedReports) {
        return switch (reportQueue.ingress(encodedReports)) {
            case ACCEPTED -> ReportIngressStatus.ACCEPTED;
            case FULL -> ReportIngressStatus.FULL;
            case CLOSED -> ReportIngressStatus.CLOSED;
        };
    }

    private void submitAtMostOneBatch() {
        List<String> batch = pendingBatch;
        if (batch == null) {
            batch = reportQueue.consume(reportQueue.capacity());
        }
        if (batch.isEmpty()) {
            return;
        }
        SubmissionOutcome outcome = submit(batch);
        pendingBatch = outcome == SubmissionOutcome.RETRY ? batch : null;
    }

    private SubmissionOutcome submit(List<String> batch) {
        try {
            remoteApi.append(adapterId, batch);
            return SubmissionOutcome.SUCCESS;
        } catch (RuntimeException error) {
            WorkerDeliveryAdapterException failure = classify(error);
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "errorCode={0} operation={1} adapterId={2} message={3}",
                    failure.errorCode().code(),
                    failure.operation(),
                    adapterId,
                    failure.getMessage()
            );
            return failure.errorCode()
                    == WorkerDeliveryAdapterErrorCode
                    .REMOTE_API_PROTOCOL_ERROR
                    ? SubmissionOutcome.DROP
                    : SubmissionOutcome.RETRY;
        }
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
