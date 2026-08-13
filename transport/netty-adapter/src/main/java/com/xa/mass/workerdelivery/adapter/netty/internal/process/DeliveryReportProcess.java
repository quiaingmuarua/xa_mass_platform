package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.process.FiniteQueue.QueueIngressStatus.ACCEPTED;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient.ResultIngress;
import java.util.List;
import java.util.Objects;

/** Scheduled Result ingress process for one Adapter instance. */
public final class DeliveryReportProcess {

    private static final System.Logger LOGGER = System.getLogger(
            DeliveryReportProcess.class.getName()
    );

    private final FiniteQueue<String> reportQueue;
    private final ResultIngress resultIngress;
    private final String adapterId;
    private final Acceptor acceptor = this::accept;
    private List<String> pendingBatch;
    private boolean closeFinished;

    public DeliveryReportProcess(
            ResultIngress resultIngress,
            String adapterId,
            int queueCapacity
    ) {
        this.resultIngress = Objects.requireNonNull(
                resultIngress,
                "resultIngress"
        );
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must be non-blank");
        }
        this.adapterId = adapterId;
        reportQueue = new FiniteQueue<>(queueCapacity);
    }

    public Acceptor acceptor() {
        return acceptor;
    }

    public void round() {
        if (closeFinished) {
            return;
        }
        submitAtMostOneBatch();
    }

    public void stopIngress() {
        reportQueue.stopIngress();
    }

    public synchronized void finishCloseAfterSchedulerStop() {
        if (closeFinished) {
            return;
        }
        stopIngress();

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
        closeFinished = true;
    }

    private ReportIngressStatus accept(List<String> encodedReports) {
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
            resultIngress.ingress(adapterId, batch);
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
                    == WorkerDeliveryAdapterErrorCode.GATEWAY_PROTOCOL_ERROR
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
                WorkerDeliveryAdapterErrorCode.GATEWAY_UNAVAILABLE,
                "adapter.submitResults",
                "Worker result submission failed",
                error
        );
    }

    public interface Acceptor {

        ReportIngressStatus ingress(List<String> encodedReports);
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
