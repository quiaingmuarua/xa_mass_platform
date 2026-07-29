package com.xa.mass.workerdelivery.adapter.result;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultSource;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

public final class WorkerResultLoop implements Runnable {

    private static final System.Logger LOGGER = System.getLogger(
            WorkerResultLoop.class.getName()
    );

    private final WorkerDeliveryGatewayClient gateway;
    private final String adapterId;
    private final BoundedWorkerResultQueue resultQueue;
    private final EnumMap<SeedResultSource, List<String>> pendingBatches =
            new EnumMap<>(SeedResultSource.class);
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
        for (SeedResultSource source : SeedResultSource.values()) {
            submitAtMostOneBatch(source);
        }
    }

    public void stopAccepting() {
        resultQueue.stopAccepting();
    }

    public synchronized void closeAndFlush() {
        if (closed) {
            return;
        }
        resultQueue.stopAccepting();

        for (SeedResultSource source : SeedResultSource.values()) {
            List<String> pending = pendingBatches.get(source);
            if (pending != null) {
                SubmissionOutcome outcome = submit(source, pending);
                if (outcome == SubmissionOutcome.RETRY) {
                    continue;
                }
                pendingBatches.remove(source);
            }

            List<String> remaining = resultQueue.drain(source);
            if (remaining.isEmpty()) {
                continue;
            }
            if (submit(source, remaining) == SubmissionOutcome.RETRY) {
                pendingBatches.put(source, remaining);
            }
        }
        closed = true;
    }

    List<String> pendingBatch(SeedResultSource source) {
        return pendingBatches.get(source);
    }

    private void submitAtMostOneBatch(SeedResultSource source) {
        List<String> batch = pendingBatches.get(source);
        if (batch == null) {
            batch = resultQueue.drain(source);
        }
        if (batch.isEmpty()) {
            return;
        }
        SubmissionOutcome outcome = submit(source, batch);
        if (outcome == SubmissionOutcome.RETRY) {
            pendingBatches.put(source, batch);
        } else {
            pendingBatches.remove(source);
        }
    }

    private SubmissionOutcome submit(
            SeedResultSource source,
            List<String> batch
    ) {
        try {
            gateway.appendResults(adapterId, source, batch);
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
