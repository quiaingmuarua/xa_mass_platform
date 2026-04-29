package com.xa.mass.transport.model;

import java.util.Objects;

/**
 * Adapter-neutral result of handing one transport dispatch envelope to a
 * transport.
 */
public final class DispatchOutcome {

    private final String deliveryId;
    private final String adapterId;
    private final String routeKey;
    private final String correlationKey;
    private final DispatchOutcomeStatus status;
    private final boolean retryable;
    private final String reason;

    public DispatchOutcome(String deliveryId,
                           String adapterId,
                           String routeKey,
                           String correlationKey,
                           DispatchOutcomeStatus status,
                           boolean retryable,
                           String reason) {
        this.deliveryId = normalizeText(deliveryId);
        this.adapterId = TransportDeliveryAddressing.normalizeAdapterId(adapterId);
        this.routeKey = TransportDeliveryAddressing.normalizeRouteKey(routeKey);
        this.correlationKey = normalizeText(correlationKey);
        this.status = Objects.requireNonNull(status, "status");
        this.retryable = retryable;
        this.reason = reason;
    }

    public static DispatchOutcome sent(String adapterId, TransportDispatchEnvelope envelope) {
        return fromEnvelope(adapterId, envelope, DispatchOutcomeStatus.SENT, false, null);
    }

    public static DispatchOutcome queued(String adapterId, TransportDispatchEnvelope envelope) {
        return fromEnvelope(adapterId, envelope, DispatchOutcomeStatus.QUEUED, false, null);
    }

    public static DispatchOutcome endpointOffline(String adapterId, TransportDispatchEnvelope envelope, String reason) {
        return fromEnvelope(adapterId, envelope, DispatchOutcomeStatus.ENDPOINT_OFFLINE, true, reason);
    }

    public static DispatchOutcome backpressureRejected(String adapterId, TransportDispatchEnvelope envelope, String reason) {
        return fromEnvelope(adapterId, envelope, DispatchOutcomeStatus.BACKPRESSURE_REJECTED, true, reason);
    }

    public static DispatchOutcome invalid(String adapterId, TransportDispatchEnvelope envelope, String reason) {
        return fromEnvelope(adapterId, envelope, DispatchOutcomeStatus.INVALID_ITEM, false, reason);
    }

    public static DispatchOutcome adapterUnavailable(String adapterId, TransportDispatchEnvelope envelope, String reason) {
        return fromEnvelope(adapterId, envelope, DispatchOutcomeStatus.ADAPTER_UNAVAILABLE, true, reason);
    }

    public static DispatchOutcome failed(String adapterId,
                                         TransportDispatchEnvelope envelope,
                                         String reason,
                                         boolean retryable) {
        return fromEnvelope(adapterId, envelope, DispatchOutcomeStatus.FAILED, retryable, reason);
    }

    private static DispatchOutcome fromEnvelope(String adapterId,
                                                TransportDispatchEnvelope envelope,
                                                DispatchOutcomeStatus status,
                                                boolean retryable,
                                                String reason) {
        return new DispatchOutcome(
                envelope != null ? envelope.getDeliveryId() : null,
                adapterId,
                envelope != null ? envelope.getRouteKey() : null,
                envelope != null ? envelope.getCorrelationKey() : null,
                status,
                retryable,
                reason
        );
    }

    public String getDeliveryId() {
        return deliveryId;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public String getCorrelationKey() {
        return correlationKey;
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

    private static String normalizeText(String value) {
        return TransportDeliveryAddressing.normalizeText(value);
    }
}
