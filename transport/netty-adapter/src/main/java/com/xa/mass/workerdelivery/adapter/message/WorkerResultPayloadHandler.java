package com.xa.mass.workerdelivery.adapter.message;

import static com.xa.mass.workerdelivery.adapter.result.BoundedWorkerResultQueue.OfferStatus.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.result.BoundedWorkerResultQueue.OfferStatus.CLOSED;
import static com.xa.mass.workerdelivery.adapter.result.BoundedWorkerResultQueue.OfferStatus.FULL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultSource.WORKER;

import com.xa.mass.workerdelivery.adapter.result.BoundedWorkerResultQueue;
import java.util.Objects;

public final class WorkerResultPayloadHandler
        implements AdapterMessageDefinition.Handler<
                String,
                WorkerResultHandlingResult> {

    private final BoundedWorkerResultQueue resultQueue;

    public WorkerResultPayloadHandler(
            BoundedWorkerResultQueue resultQueue
    ) {
        this.resultQueue = Objects.requireNonNull(
                resultQueue,
                "resultQueue"
        );
    }

    @Override
    public WorkerResultHandlingResult handle(
            String workerId,
            String encodedSeedResult
    ) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException(
                    "workerId must be non-blank"
            );
        }
        return switch (resultQueue.offer(WORKER, encodedSeedResult)) {
            case ACCEPTED -> WorkerResultHandlingResult.ACCEPTED;
            case FULL -> WorkerResultHandlingResult.BUFFER_FULL;
            case CLOSED -> WorkerResultHandlingResult.ADAPTER_CLOSED;
        };
    }
}
