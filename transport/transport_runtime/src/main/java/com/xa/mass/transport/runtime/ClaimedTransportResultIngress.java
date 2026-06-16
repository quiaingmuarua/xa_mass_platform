package com.xa.mass.transport.runtime;

import com.xa.mass.transport.model.TransportResultIngressEnvelope;

/**
 * One claimed result inbox item.
 */
public record ClaimedTransportResultIngress(String claimRef,
                                            TransportResultIngressEnvelope envelope) {
    public ClaimedTransportResultIngress {
        if (claimRef == null || claimRef.isBlank()) {
            throw new IllegalArgumentException("claimRef must not be blank");
        }
        if (envelope == null) {
            throw new IllegalArgumentException("envelope must not be null");
        }
    }
}
