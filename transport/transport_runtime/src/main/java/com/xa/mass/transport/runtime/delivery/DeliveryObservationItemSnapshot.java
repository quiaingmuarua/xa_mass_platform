package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.TaskDispatchContent;
import com.xa.mass.transport.model.TaskDispatchExecutionContext;
import com.xa.mass.transport.model.TransportDeliveryAddressing;

/**
 * Item-level observation facts for delivery outcome and failure events.
 */
public record DeliveryObservationItemSnapshot(String commandId,
                                              String selectedWorkerId,
                                              String taskId,
                                              String messageId,
                                              String attemptId,
                                              int attemptNo,
                                              String routeKey,
                                              String connectionId) {

    public DeliveryObservationItemSnapshot {
        commandId = requireText(commandId, "commandId");
        selectedWorkerId = normalizeText(selectedWorkerId);
        taskId = requireText(taskId, "taskId");
        messageId = requireText(messageId, "messageId");
        attemptId = normalizeText(attemptId);
        attemptNo = Math.max(0, attemptNo);
        routeKey = TransportDeliveryAddressing.normalizeRouteKey(routeKey);
        connectionId = normalizeText(connectionId);
    }

    public static DeliveryObservationItemSnapshot from(DeliveryCommand command, EndpointLease endpoint) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        TaskDispatchContent content = command.getContent();
        TaskDispatchExecutionContext executionContext = command.getExecutionContext();
        return new DeliveryObservationItemSnapshot(
                command.getCommandId(),
                command.getSelectedWorkerId(),
                content.taskId(),
                content.messageId(),
                executionContext.attemptId(),
                executionContext.attemptNo(),
                endpoint != null ? endpoint.routeKey() : null,
                endpoint != null ? endpoint.connectionId() : null
        );
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
