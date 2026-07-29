package com.xa.mass.workerdelivery.adapter.result;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import java.util.List;
import java.util.Objects;

public final class WorkerResultLoop implements Runnable {

    private static final System.Logger LOGGER = System.getLogger(
            WorkerResultLoop.class.getName()
    );

    private final WorkerDeliveryGatewayClient gateway;
    private final String adapterId;
    private final BoundedWorkerResultQueue resultQueue;
    private List<SeedResult> pendingBatch;
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

        if (pendingBatch != null && !submit(pendingBatch)) {
            closed = true;
            return;
        }
        pendingBatch = null;

        List<SeedResult> remaining = resultQueue.drainAll();
        if (!remaining.isEmpty() && !submit(remaining)) {
            pendingBatch = remaining;
        }
        closed = true;
    }

    List<SeedResult> pendingBatch() {
        return pendingBatch;
    }

    private void submitAtMostOneBatch() {
        List<SeedResult> batch = pendingBatch;
        if (batch == null) {
            batch = resultQueue.drainAll();
        }
        if (batch.isEmpty()) {
            return;
        }
        if (submit(batch)) {
            pendingBatch = null;
        } else {
            pendingBatch = batch;
        }
    }

    private boolean submit(List<SeedResult> batch) {
        try {
            gateway.appendResults(adapterId, batch);
            return true;
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
            return false;
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
}
