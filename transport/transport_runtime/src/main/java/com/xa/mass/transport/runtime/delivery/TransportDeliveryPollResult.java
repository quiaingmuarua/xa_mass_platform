package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.TransportDispatchEnvelope;

import java.util.List;
import java.util.Objects;

public final class TransportDeliveryPollResult {

    private final TransportDeliveryPollStatus status;
    private final List<TransportDispatchEnvelope> envelopes;

    public TransportDeliveryPollResult(TransportDeliveryPollStatus status, List<TransportDispatchEnvelope> envelopes) {
        this.status = Objects.requireNonNull(status, "status");
        this.envelopes = envelopes == null || envelopes.isEmpty() ? List.of() : List.copyOf(envelopes);
    }

    public static TransportDeliveryPollResult delivered(List<TransportDispatchEnvelope> envelopes) {
        return new TransportDeliveryPollResult(TransportDeliveryPollStatus.DELIVERED, envelopes);
    }

    public static TransportDeliveryPollResult empty() {
        return new TransportDeliveryPollResult(TransportDeliveryPollStatus.EMPTY, List.of());
    }

    public static TransportDeliveryPollResult invalidRequest() {
        return new TransportDeliveryPollResult(TransportDeliveryPollStatus.INVALID_REQUEST, List.of());
    }

    public static TransportDeliveryPollResult unavailable() {
        return new TransportDeliveryPollResult(TransportDeliveryPollStatus.UNAVAILABLE, List.of());
    }

    public static TransportDeliveryPollResult shutdown() {
        return new TransportDeliveryPollResult(TransportDeliveryPollStatus.SHUTDOWN, List.of());
    }

    public TransportDeliveryPollStatus getStatus() {
        return status;
    }

    public List<TransportDispatchEnvelope> getEnvelopes() {
        return envelopes;
    }
}
