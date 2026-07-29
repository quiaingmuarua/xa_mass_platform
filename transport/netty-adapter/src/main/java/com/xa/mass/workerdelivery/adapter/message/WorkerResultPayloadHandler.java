package com.xa.mass.workerdelivery.adapter.message;

import static com.xa.mass.workerdelivery.adapter.result.BoundedWorkerResultQueue.OfferStatus.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.result.BoundedWorkerResultQueue.OfferStatus.CLOSED;
import static com.xa.mass.workerdelivery.adapter.result.BoundedWorkerResultQueue.OfferStatus.FULL;

import com.xa.mass.workerdelivery.adapter.result.BoundedWorkerResultQueue;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResultOutcomeClass;
import java.util.Objects;

public final class WorkerResultPayloadHandler {

    private final BoundedWorkerResultQueue resultQueue;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    public WorkerResultPayloadHandler(
            BoundedWorkerResultQueue resultQueue
    ) {
        this.resultQueue = Objects.requireNonNull(
                resultQueue,
                "resultQueue"
        );
    }

    public WorkerResultHandlingResult handle(
            String workerId,
            String encodedWorkerResult
    ) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException(
                "workerId must be non-blank"
            );
        }
        WorkerResult result = codec.decodeWorkerResult(encodedWorkerResult);
        if (result == null
                || WorkerDeliveryProtocol.classifyWorkerResultOutcomeCode(
                result.outcomeCode()
        ) == WorkerResultOutcomeClass.ADAPTER_REJECTION) {
            return WorkerResultHandlingResult.INVALID_RESULT;
        }
        return switch (resultQueue.offer(encodedWorkerResult)) {
            case ACCEPTED -> WorkerResultHandlingResult.ACCEPTED;
            case FULL -> WorkerResultHandlingResult.BUFFER_FULL;
            case CLOSED -> WorkerResultHandlingResult.ADAPTER_CLOSED;
        };
    }
}
