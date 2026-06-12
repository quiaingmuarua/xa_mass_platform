package com.xa.mass.transport.model;

import java.util.Objects;

/**
 * Adapter-neutral result of attempting one transport delivery.
 */
public final class DispatchOutcome {

    private final String deliveryId;
    private final String adapterId;
    private final String selectedWorkerId;
    private final String deliveryQueueKey;
    private final String routeKey;
    private final String attemptId;
    private final String taskId;
    private final String messageId;
    private final int attemptNo;
    private final DispatchOutcomeStatus status;
    private final boolean retryable;
    private final String reason;
    private final String transportNodeId;
    private final String connectionId;
    private final long occurredAtEpochMillis;

    public DispatchOutcome(String deliveryId,
                           String adapterId,
                           String selectedWorkerId,
                           String deliveryQueueKey,
                           String routeKey,
                           String attemptId,
                           String taskId,
                           String messageId,
                           int attemptNo,
                           DispatchOutcomeStatus status,
                           boolean retryable,
                           String reason,
                           String transportNodeId,
                           String connectionId,
                           long occurredAtEpochMillis) {
        this.deliveryId = normalizeText(deliveryId);
        this.adapterId = TransportDeliveryAddressing.normalizeAdapterId(adapterId);
        this.selectedWorkerId = normalizeText(selectedWorkerId);
        this.deliveryQueueKey = normalizeText(deliveryQueueKey);
        this.routeKey = TransportDeliveryAddressing.normalizeRouteKey(routeKey);
        this.attemptId = normalizeText(attemptId);
        this.taskId = normalizeText(taskId);
        this.messageId = normalizeText(messageId);
        this.attemptNo = Math.max(0, attemptNo);
        this.status = Objects.requireNonNull(status, "status");
        this.retryable = retryable;
        this.reason = reason;
        this.transportNodeId = normalizeText(transportNodeId);
        this.connectionId = normalizeText(connectionId);
        this.occurredAtEpochMillis = Math.max(0L, occurredAtEpochMillis);
    }

    public static DispatchOutcome delivered(String adapterId, AdapterDispatchRequest request) {
        return fromRequest(adapterId, null, request, DispatchOutcomeStatus.DELIVERED, false, null);
    }

    public static DispatchOutcome queued(String adapterId,
                                         String deliveryQueueKey,
                                         String deliveryId,
                                         String selectedWorkerId,
                                         String attemptId,
                                         String taskId,
                                         String messageId,
                                         int attemptNo) {
        return basic(
                deliveryId,
                adapterId,
                deliveryQueueKey,
                selectedWorkerId,
                attemptId,
                taskId,
                messageId,
                attemptNo,
                DispatchOutcomeStatus.QUEUED,
                false,
                null
        );
    }

    public static DispatchOutcome noEndpoint(String adapterId, AdapterDispatchRequest request, String reason) {
        return fromRequest(adapterId, null, request, DispatchOutcomeStatus.NO_ENDPOINT, true, reason);
    }

    public static DispatchOutcome backpressure(String adapterId,
                                               String deliveryQueueKey,
                                               String deliveryId,
                                               String selectedWorkerId,
                                               String attemptId,
                                               String taskId,
                                               String messageId,
                                               int attemptNo,
                                               String reason) {
        return basic(
                deliveryId,
                adapterId,
                deliveryQueueKey,
                selectedWorkerId,
                attemptId,
                taskId,
                messageId,
                attemptNo,
                DispatchOutcomeStatus.BACKPRESSURE,
                true,
                reason
        );
    }

    public static DispatchOutcome invalid(String adapterId,
                                          String deliveryQueueKey,
                                          String deliveryId,
                                          String selectedWorkerId,
                                          String attemptId,
                                          String taskId,
                                          String messageId,
                                          int attemptNo,
                                          String reason) {
        return basic(
                deliveryId,
                adapterId,
                deliveryQueueKey,
                selectedWorkerId,
                attemptId,
                taskId,
                messageId,
                attemptNo,
                DispatchOutcomeStatus.INVALID,
                false,
                reason
        );
    }

    public static DispatchOutcome invalid(String adapterId, AdapterDispatchRequest request, String reason) {
        return fromRequest(adapterId, null, request, DispatchOutcomeStatus.INVALID, false, reason);
    }

    public static DispatchOutcome unavailable(String adapterId, AdapterDispatchRequest request, String reason) {
        return fromRequest(adapterId, null, request, DispatchOutcomeStatus.UNAVAILABLE, true, reason);
    }

    public static DispatchOutcome unavailable(String adapterId,
                                              String deliveryQueueKey,
                                              AdapterDispatchRequest request,
                                              String reason) {
        return fromRequest(adapterId, deliveryQueueKey, request, DispatchOutcomeStatus.UNAVAILABLE, true, reason);
    }

    public static DispatchOutcome fromCommand(String adapterId,
                                              String deliveryQueueKey,
                                              String targetTransportNodeId,
                                              DeliveryCommand command,
                                              AdapterEndpoint endpoint,
                                              DispatchOutcomeStatus status,
                                              boolean retryable,
                                              String reason) {
        Objects.requireNonNull(command, "command");
        TaskDispatchContent content = command.getContent();
        TaskDispatchExecutionContext executionContext = command.getExecutionContext();
        return new DispatchOutcome(
                command.getCommandId(),
                adapterId,
                command.getSelectedWorkerId(),
                deliveryQueueKey,
                endpoint != null ? endpoint.routeKey() : null,
                executionContext.attemptId(),
                content.taskId(),
                content.messageId(),
                executionContext.attemptNo(),
                status,
                retryable,
                reason,
                endpoint != null ? endpoint.transportNodeId() : targetTransportNodeId,
                endpoint != null ? endpoint.connectionId() : null,
                System.currentTimeMillis()
        );
    }

    public static DispatchOutcome failed(String adapterId,
                                         AdapterDispatchRequest request,
                                         String reason,
                                         boolean retryable) {
        return fromRequest(adapterId, null, request, DispatchOutcomeStatus.FAILED, retryable, reason);
    }

    public static DispatchOutcome shutdown(String adapterId, AdapterDispatchRequest request, String reason) {
        return fromRequest(adapterId, null, request, DispatchOutcomeStatus.SHUTDOWN, true, reason);
    }

    private static DispatchOutcome fromRequest(String adapterId,
                                               String deliveryQueueKey,
                                               AdapterDispatchRequest request,
                                               DispatchOutcomeStatus status,
                                               boolean retryable,
                                               String reason) {
        return new DispatchOutcome(
                request != null ? request.deliveryId() : null,
                adapterId,
                request != null ? request.selectedWorkerId() : null,
                deliveryQueueKey,
                request != null ? request.endpoint().routeKey() : null,
                request != null ? request.executionContext().attemptId() : null,
                request != null ? request.content().taskId() : null,
                request != null ? request.content().messageId() : null,
                request != null ? request.executionContext().attemptNo() : 0,
                status,
                retryable,
                reason,
                request != null ? request.endpoint().transportNodeId() : null,
                request != null ? request.endpoint().connectionId() : null,
                System.currentTimeMillis()
        );
    }

    private static DispatchOutcome basic(String deliveryId,
                                         String adapterId,
                                         String deliveryQueueKey,
                                         String selectedWorkerId,
                                         String attemptId,
                                         String taskId,
                                         String messageId,
                                         int attemptNo,
                                         DispatchOutcomeStatus status,
                                         boolean retryable,
                                         String reason) {
        return new DispatchOutcome(
                deliveryId,
                adapterId,
                selectedWorkerId,
                deliveryQueueKey,
                null,
                attemptId,
                taskId,
                messageId,
                attemptNo,
                status,
                retryable,
                reason,
                null,
                null,
                System.currentTimeMillis()
        );
    }

    public String getDeliveryId() {
        return deliveryId;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getSelectedWorkerId() {
        return selectedWorkerId;
    }

    public String getDeliveryQueueKey() {
        return deliveryQueueKey;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getMessageId() {
        return messageId;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public DispatchOutcomeStatus getStatus() {
        return status;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String getReason() {
        return reason;
    }

    public String getTransportNodeId() {
        return transportNodeId;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public long getOccurredAtEpochMillis() {
        return occurredAtEpochMillis;
    }

    private static String normalizeText(String value) {
        return TransportDeliveryAddressing.normalizeText(value);
    }
}
