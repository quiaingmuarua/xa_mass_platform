package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.TransportDispatchEnvelope;

import java.util.List;
import java.util.Objects;

public final class TransportDeliveryPollResult {

    private final TransportDeliveryPollStatus status;
    private final List<TransportDispatchEnvelope> envelopes;

    private TransportDeliveryPollResult(TransportDeliveryPollStatus status, List<TransportDispatchEnvelope> envelopes) {
        this(status, envelopes, false);
    }

    private TransportDeliveryPollResult(TransportDeliveryPollStatus status,
                                        List<TransportDispatchEnvelope> envelopes,
                                        boolean trustedView) {
        this.status = Objects.requireNonNull(status, "status");
        if (envelopes == null || envelopes.isEmpty()) {
            this.envelopes = List.of();
            return;
        }
        this.envelopes = trustedView ? envelopes : List.copyOf(envelopes);
    }

    public static TransportDeliveryPollResult of(TransportDeliveryPollStatus status,
                                                 List<TransportDispatchEnvelope> envelopes) {
        Objects.requireNonNull(status, "status");
        return switch (status) {
            case DELIVERED -> delivered(envelopes);
            case EMPTY -> empty();
            case INVALID_REQUEST -> invalidRequest();
            case UNAVAILABLE -> unavailable();
            case SHUTDOWN -> shutdown();
        };
    }

    public static TransportDeliveryPollResult delivered(List<TransportDispatchEnvelope> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) {
            throw new IllegalArgumentException("delivered poll result must include at least one envelope");
        }
        return new TransportDeliveryPollResult(TransportDeliveryPollStatus.DELIVERED, envelopes);
    }

    static TransportDeliveryPollResult deliveredView(List<TransportDispatchEnvelope> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) {
            throw new IllegalArgumentException("delivered poll result must include at least one envelope");
        }
        return new TransportDeliveryPollResult(TransportDeliveryPollStatus.DELIVERED, envelopes, true);
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
