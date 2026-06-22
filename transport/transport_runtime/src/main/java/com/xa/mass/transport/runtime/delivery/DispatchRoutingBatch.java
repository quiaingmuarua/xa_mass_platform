package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.routing.RoutingOwnerKinds;
import com.xa.mass.transport.routing.RoutingTarget;

import java.util.List;
import java.util.Objects;

/**
 * Producer/queue dispatch carrier for one adapter mailbox target.
 */
public record DispatchRoutingBatch(RoutingTarget target,
                                   List<DispatchRoutingItem> items) {

    public DispatchRoutingBatch {
        target = Objects.requireNonNull(target, "target");
        if (!RoutingOwnerKinds.ADAPTER_MAILBOX.equals(target.ownerKind())) {
            throw new IllegalArgumentException("dispatch target must be adapter-mailbox");
        }
        if (items != null) {
            for (DispatchRoutingItem item : items) {
                if (item == null) {
                    throw new IllegalArgumentException("items must not contain null");
                }
            }
        }
        items = items == null ? List.of() : List.copyOf(items);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
    }

    public String adapterMailboxKey() {
        return target.ownerRef();
    }
}
