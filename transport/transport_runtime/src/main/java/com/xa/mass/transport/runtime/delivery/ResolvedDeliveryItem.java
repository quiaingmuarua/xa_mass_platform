package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;

import java.util.Objects;

/**
 * Resolved handoff item: minimal command plus per-item endpoint evidence.
 */
public record ResolvedDeliveryItem(DeliveryCommand command, EndpointLease endpoint) {

    public ResolvedDeliveryItem {
        command = Objects.requireNonNull(command, "command");
        endpoint = Objects.requireNonNull(endpoint, "endpoint");
    }
}
