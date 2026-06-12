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
    private final DispatchOutcomeStatus status;
    private final boolean retryable;
    private final String reason;
    private final String transportNodeId;
    private final String connectionId;
    private final long occurredAtEpochMillis;

    public DispatchOutcome(String deliveryId,
                           String adapterId,
                           String routeKey,
                           String attemptId,
                           DispatchOutcomeStatus status,
                           boolean retryable,
                           String reason) {
        this(
                deliveryId,
                adapterId,
                null,
                null,
                routeKey,
                attemptId,
                status,
                retryable,
                reason,
                null,
                null,
                0L
        );
    }

    public DispatchOutcome(String deliveryId,
                           String adapterId,
                           String selectedWorkerId,
                           String deliveryQueueKey,
                           String routeKey,
                           String attemptId,
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
        this.status = Objects.requireNonNull(status, "status");
        this.retryable = retryable;
        this.reason = reason;
        this.transportNodeId = normalizeText(transportNodeId);
        this.connectionId = normalizeText(connectionId);
        this.occurredAtEpochMillis = Math.max(0L, occurredAtEpochMillis);
    }

    public static DispatchOutcome delivered(String adapterId, TransportDispatchEnvelope envelope) {
        return fromEnvelope(adapterId, envelope, DispatchOutcomeStatus.DELIVERED, false, null);
    }

    public static DispatchOutcome queued(String adapterId, TransportDispatchEnvelope envelope) {
        return fromEnvelope(adapterId, envelope, DispatchOutcomeStatus.QUEUED, false, null);
    }

    public static DispatchOutcome queued(DeliveryCommand command) {
        return fromCommand(command, DispatchOutcomeStatus.QUEUED, false, null);
    }

    public static DispatchOutcome noEndpoint(String adapterId, TransportDispatchEnvelope envelope, String reason) {
        return fromEnvelope(adapterId, envelope, DispatchOutcomeStatus.NO_ENDPOINT, true, reason);
    }

    public static DispatchOutcome noEndpoint(DeliveryCommand command, String reason) {
        return fromCommand(command, DispatchOutcomeStatus.NO_ENDPOINT, true, reason);
    }

    public static DispatchOutcome backpressure(String adapterId, TransportDispatchEnvelope envelope, String reason) {
        return fromEnvelope(adapterId, envelope, DispatchOutcomeStatus.BACKPRESSURE, true, reason);
    }

    public static DispatchOutcome backpressure(DeliveryCommand command, String reason) {
        return fromCommand(command, DispatchOutcomeStatus.BACKPRESSURE, true, reason);
    }

    public static DispatchOutcome invalid(String adapterId, TransportDispatchEnvelope envelope, String reason) {
        return fromEnvelope(adapterId, envelope, DispatchOutcomeStatus.INVALID, false, reason);
    }

    public static DispatchOutcome invalid(DeliveryCommand command, String reason) {
        return fromCommand(command, DispatchOutcomeStatus.INVALID, false, reason);
    }

    public static DispatchOutcome unavailable(String adapterId, TransportDispatchEnvelope envelope, String reason) {
        return fromEnvelope(adapterId, envelope, DispatchOutcomeStatus.UNAVAILABLE, true, reason);
    }

    public static DispatchOutcome unavailable(DeliveryCommand command, String reason) {
        return fromCommand(command, DispatchOutcomeStatus.UNAVAILABLE, true, reason);
    }

    public static DispatchOutcome failed(String adapterId,
                                         TransportDispatchEnvelope envelope,
                                         String reason,
                                         boolean retryable) {
        return fromEnvelope(adapterId, envelope, DispatchOutcomeStatus.FAILED, retryable, reason);
    }

    public static DispatchOutcome shutdown(String adapterId, TransportDispatchEnvelope envelope, String reason) {
        return fromEnvelope(adapterId, envelope, DispatchOutcomeStatus.SHUTDOWN, true, reason);
    }

    public static DispatchOutcome shutdown(DeliveryCommand command, String reason) {
        return fromCommand(command, DispatchOutcomeStatus.SHUTDOWN, true, reason);
    }

    private static DispatchOutcome fromEnvelope(String adapterId,
                                                TransportDispatchEnvelope envelope,
                                                DispatchOutcomeStatus status,
                                                boolean retryable,
                                                String reason) {
        return new DispatchOutcome(
                envelope != null ? envelope.getDeliveryId() : null,
                adapterId,
                envelope != null ? envelope.getSelectedWorkerId() : null,
                envelope != null ? envelope.getDeliveryQueueKey() : null,
                envelope != null ? envelope.getRouteKey() : null,
                envelope != null ? envelope.getAttemptId() : null,
                status,
                retryable,
                reason,
                null,
                null,
                System.currentTimeMillis()
        );
    }

    private static DispatchOutcome fromCommand(DeliveryCommand command,
                                               DispatchOutcomeStatus status,
                                               boolean retryable,
                                               String reason) {
        return new DispatchOutcome(
                command != null ? command.getCommandId() : null,
                command != null ? command.getAdapterId() : null,
                command != null ? command.getSelectedWorkerId() : null,
                command != null ? command.getDeliveryQueueKey() : null,
                command != null ? command.getRouteKey() : null,
                command != null && command.getPayload() != null ? command.getPayload().attemptId() : null,
                status,
                retryable,
                reason,
                command != null ? command.getTargetTransportNodeId() : null,
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
