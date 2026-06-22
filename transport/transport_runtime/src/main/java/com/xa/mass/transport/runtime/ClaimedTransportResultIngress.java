package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.ResultIngressEntry;

/**
 * One claimed result inbox item.
 */
public record ClaimedTransportResultIngress(String claimRef,
                                            ResultIngressEntry entry) {
    public ClaimedTransportResultIngress {
        if (claimRef == null || claimRef.isBlank()) {
            throw new IllegalArgumentException("claimRef must not be blank");
        }
        if (entry == null) {
            throw new IllegalArgumentException("entry must not be null");
        }
    }
}
