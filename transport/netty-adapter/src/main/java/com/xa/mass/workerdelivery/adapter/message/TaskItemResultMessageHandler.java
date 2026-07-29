package com.xa.mass.workerdelivery.adapter.message;

import static com.xa.mass.workerdelivery.adapter.message.BoundedWorkerResultBuffer.OfferStatus.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.message.BoundedWorkerResultBuffer.OfferStatus.CLOSED;
import static com.xa.mass.workerdelivery.adapter.message.BoundedWorkerResultBuffer.OfferStatus.FULL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass.SUCCESS;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass.WORKER_FAILURE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessageType.TASK_ITEM_RESULT;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemResultMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessageType;
import java.util.Objects;

public final class TaskItemResultMessageHandler
        implements WorkerConnectionMessageHandler<TaskItemResultMessage> {

    private final BoundedWorkerResultBuffer resultBuffer;

    public TaskItemResultMessageHandler(
            BoundedWorkerResultBuffer resultBuffer
    ) {
        this.resultBuffer = Objects.requireNonNull(
                resultBuffer,
                "resultBuffer"
        );
    }

    @Override
    public WorkerConnectionMessageType messageType() {
        return TASK_ITEM_RESULT;
    }

    @Override
    public Class<TaskItemResultMessage> messageClass() {
        return TaskItemResultMessage.class;
    }

    @Override
    public WorkerMessageHandlingResult handle(
            String workerId,
            TaskItemResultMessage message
    ) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException(
                    "workerId must be non-blank"
            );
        }
        Objects.requireNonNull(message, "message");
        var outcomeClass = WorkerDeliveryProtocol.classifyOutcomeCode(
                message.result().outcomeCode()
        );
        if (outcomeClass != SUCCESS && outcomeClass != WORKER_FAILURE) {
            return WorkerMessageHandlingResult.INVALID_OUTCOME;
        }
        return switch (resultBuffer.offer(message.result())) {
            case ACCEPTED -> WorkerMessageHandlingResult.ACCEPTED;
            case FULL -> WorkerMessageHandlingResult.BUFFER_FULL;
            case CLOSED -> WorkerMessageHandlingResult.ADAPTER_CLOSED;
        };
    }
}
