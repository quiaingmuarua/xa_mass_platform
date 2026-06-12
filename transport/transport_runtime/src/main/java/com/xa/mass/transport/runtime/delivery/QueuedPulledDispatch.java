package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.channel.PulledTaskDispatch;
import com.xa.mass.transport.model.AdapterDispatchRequest;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.TaskDispatchContent;
import com.xa.mass.transport.model.TaskDispatchExecutionContext;
import com.xa.mass.transport.model.TransportDeliveryAddressing;

import java.util.Objects;

/**
 * Queue value for polling worker delivery.
 */
public final class QueuedPulledDispatch {

    private final String deliveryId;
    private final String selectedWorkerId;
    private final TaskDispatchContent content;
    private final String attemptId;
    private final int attemptNo;
    private final int retryCount;
    private final String batchId;
    private final long createdAtEpochMillis;

    public QueuedPulledDispatch(String deliveryId,
                                String selectedWorkerId,
                                TaskDispatchContent content,
                                String attemptId,
                                int attemptNo,
                                int retryCount,
                                String batchId,
                                long createdAtEpochMillis) {
        this.deliveryId = requireText(deliveryId, "deliveryId");
        this.selectedWorkerId = requireText(selectedWorkerId, "selectedWorkerId");
        this.content = Objects.requireNonNull(content, "content");
        this.attemptId = normalizeText(attemptId);
        this.attemptNo = Math.max(0, attemptNo);
        this.retryCount = Math.max(0, retryCount);
        this.batchId = normalizeText(batchId);
        this.createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
    }

    public static QueuedPulledDispatch from(AdapterDispatchRequest request) {
        Objects.requireNonNull(request, "request");
        TaskDispatchExecutionContext context = request.executionContext();
        return new QueuedPulledDispatch(
                request.deliveryId(),
                request.selectedWorkerId(),
                request.content(),
                context.attemptId(),
                context.attemptNo(),
                context.retryCount(),
                context.batchId(),
                request.createdAtEpochMillis()
        );
    }

    public static QueuedPulledDispatch from(DeliveryCommand command) {
        Objects.requireNonNull(command, "command");
        TaskDispatchExecutionContext context = command.getExecutionContext();
        return new QueuedPulledDispatch(
                command.getCommandId(),
                command.getSelectedWorkerId(),
                command.getContent(),
                context.attemptId(),
                context.attemptNo(),
                context.retryCount(),
                context.batchId(),
                command.getCreatedAtEpochMillis()
        );
    }

    public PulledTaskDispatch toPulledTaskDispatch() {
        return new PulledTaskDispatch(
                content.taskId(),
                content.messageId(),
                content.eventCode(),
                content.input(),
                content.sharedConfig(),
                attemptId,
                attemptNo,
                retryCount,
                batchId
        );
    }

    public String deliveryId() {
        return deliveryId;
    }

    public String selectedWorkerId() {
        return selectedWorkerId;
    }

    public TaskDispatchContent content() {
        return content;
    }

    public String attemptId() {
        return attemptId;
    }

    public int attemptNo() {
        return attemptNo;
    }

    public int retryCount() {
        return retryCount;
    }

    public String batchId() {
        return batchId;
    }

    public long createdAtEpochMillis() {
        return createdAtEpochMillis;
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeText(String value) {
        return TransportDeliveryAddressing.normalizeText(value);
    }
}
