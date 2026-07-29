package com.xa.mass.workerdelivery.adapter.result;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import java.util.List;
import java.util.Objects;

public final class WorkerResultLoop implements Runnable {

    private static final System.Logger LOGGER = System.getLogger(
            WorkerResultLoop.class.getName()
    );

    private final WorkerDeliveryGatewayClient gateway;
    private final String adapterId;
    private final BoundedWorkerResultQueue resultQueue;
    private List<String> pendingBatch;
    private volatile boolean closed;

    public WorkerResultLoop(
            WorkerDeliveryGatewayClient gateway,
            String adapterId,
            BoundedWorkerResultQueue resultQueue
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException(
                    "adapterId must be non-blank"
            );
        }
        this.adapterId = adapterId;
        this.resultQueue = Objects.requireNonNull(
                resultQueue,
                "resultQueue"
        );
    }

    @Override
    public synchronized void run() {
        if (closed) {
            return;
        }
        submitAtMostOneBatch();
    }

    public void stopAccepting() {
        resultQueue.stopAccepting();
    }

    public synchronized void closeAndFlush() {
        if (closed) {
            return;
        }
        resultQueue.stopAccepting();

        if (pendingBatch != null) {
            SubmissionOutcome outcome = submit(pendingBatch);
            if (outcome != SubmissionOutcome.RETRY) {
                pendingBatch = null;
            }
        }
        if (pendingBatch == null) {
            List<String> remaining = resultQueue.drain();
            if (!remaining.isEmpty()
                    && submit(remaining) == SubmissionOutcome.RETRY) {
                pendingBatch = remaining;
            }
        }
        closed = true;
    }

    List<String> pendingBatch() {
        return pendingBatch;
    }

    private void submitAtMostOneBatch() {
        List<String> batch = pendingBatch;
        if (batch == null) {
            batch = resultQueue.drain();
        }
        if (batch.isEmpty()) {
            return;
        }
        SubmissionOutcome outcome = submit(batch);
        if (outcome == SubmissionOutcome.RETRY) {
            pendingBatch = batch;
        } else {
            pendingBatch = null;
        }
    }

    private SubmissionOutcome submit(List<String> batch) {
        try {
            gateway.appendResults(adapterId, batch);
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
                    .GATEWAY_PROTOCOL_ERROR
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

    private enum SubmissionOutcome {
        SUCCESS,
        RETRY,
        DROP
    }
}
