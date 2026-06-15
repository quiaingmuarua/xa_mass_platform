package com.xa.mass.transport.model;

import java.util.Objects;

/**
 * Adapter-neutral result of attempting one transport delivery.
 */
public final class DispatchOutcome {

    private final String deliveryId;
    private final String selectedWorkerId;
    private final String correlationRef;
    private final DispatchOutcomeStatus status;
    private final boolean retryable;
    private final String reason;
    private final long occurredAtEpochMillis;

    public DispatchOutcome(String deliveryId,
                           String selectedWorkerId,
                           String correlationRef,
                           DispatchOutcomeStatus status,
                           boolean retryable,
                           String reason,
                           long occurredAtEpochMillis) {
        this.deliveryId = normalizeText(deliveryId);
        this.selectedWorkerId = normalizeText(selectedWorkerId);
        this.correlationRef = normalizeText(correlationRef);
        this.status = Objects.requireNonNull(status, "status");
        this.retryable = retryable;
        this.reason = reason;
        this.occurredAtEpochMillis = Math.max(0L, occurredAtEpochMillis);
    }

    public static DispatchOutcome delivered(AdapterDispatchRequest request) {
        return fromRequest(request, DispatchOutcomeStatus.DELIVERED, false, null);
    }

    public static DispatchOutcome queued(String deliveryId,
                                         String selectedWorkerId,
                                         String correlationRef) {
        return basic(
                deliveryId,
                selectedWorkerId,
                correlationRef,
                DispatchOutcomeStatus.QUEUED,
                false,
                null
        );
    }

    public static DispatchOutcome noEndpoint(AdapterDispatchRequest request, String reason) {
        return fromRequest(request, DispatchOutcomeStatus.NO_ENDPOINT, true, reason);
    }

    public static DispatchOutcome backpressure(String deliveryId,
                                               String selectedWorkerId,
                                               String correlationRef,
                                               String reason) {
        return basic(
                deliveryId,
                selectedWorkerId,
                correlationRef,
                DispatchOutcomeStatus.BACKPRESSURE,
                true,
                reason
        );
    }

    public static DispatchOutcome invalid(String deliveryId,
                                          String selectedWorkerId,
                                          String correlationRef,
                                          String reason) {
        return basic(
                deliveryId,
                selectedWorkerId,
                correlationRef,
                DispatchOutcomeStatus.INVALID,
                false,
                reason
        );
    }

    public static DispatchOutcome invalid(AdapterDispatchRequest request, String reason) {
        return fromRequest(request, DispatchOutcomeStatus.INVALID, false, reason);
    }

    public static DispatchOutcome unavailable(AdapterDispatchRequest request, String reason) {
        return fromRequest(request, DispatchOutcomeStatus.UNAVAILABLE, true, reason);
    }

    public static DispatchOutcome fromCommand(DeliveryCommand command,
                                              DispatchOutcomeStatus status,
                                              boolean retryable,
                                              String reason) {
        Objects.requireNonNull(command, "command");
        return new DispatchOutcome(
                command.getCommandId(),
                command.getSelectedWorkerId(),
                command.getCorrelationRef(),
                status,
                retryable,
                reason,
                System.currentTimeMillis()
        );
    }

    public static DispatchOutcome failed(AdapterDispatchRequest request,
                                         String reason,
                                         boolean retryable) {
        return fromRequest(request, DispatchOutcomeStatus.FAILED, retryable, reason);
    }

    public static DispatchOutcome shutdown(AdapterDispatchRequest request, String reason) {
        return fromRequest(request, DispatchOutcomeStatus.SHUTDOWN, true, reason);
    }

    private static DispatchOutcome fromRequest(AdapterDispatchRequest request,
                                               DispatchOutcomeStatus status,
                                               boolean retryable,
                                               String reason) {
        return new DispatchOutcome(
                request != null ? request.deliveryId() : null,
                request != null ? request.selectedWorkerId() : null,
                request != null ? request.correlationRef() : null,
                status,
                retryable,
                reason,
                System.currentTimeMillis()
        );
    }

    private static DispatchOutcome basic(String deliveryId,
                                         String selectedWorkerId,
                                         String correlationRef,
                                         DispatchOutcomeStatus status,
                                         boolean retryable,
                                         String reason) {
        return new DispatchOutcome(
                deliveryId,
                selectedWorkerId,
                correlationRef,
                status,
                retryable,
                reason,
                System.currentTimeMillis()
        );
    }

    public String getDeliveryId() {
        return deliveryId;
    }

    public String getSelectedWorkerId() {
        return selectedWorkerId;
    }

    public String getCorrelationRef() {
        return correlationRef;
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

    public long getOccurredAtEpochMillis() {
        return occurredAtEpochMillis;
    }

    private static String normalizeText(String value) {
        return TransportDeliveryAddressing.normalizeText(value);
    }
}
