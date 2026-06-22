package com.xa.mass.transport.runtime.delivery;

import java.util.List;
import java.util.Objects;

/**
 * Consumer materialization of a dispatch batch plus handoff claim references.
 */
public record ClaimedDispatchRoutingBatch(DispatchRoutingBatch batch,
                                          List<DispatchHandoffReference> references) {

    public ClaimedDispatchRoutingBatch {
        batch = Objects.requireNonNull(batch, "batch");
        if (references != null) {
            for (DispatchHandoffReference reference : references) {
                if (reference == null) {
                    throw new IllegalArgumentException("references must not contain null");
                }
            }
        }
        references = references == null ? List.of() : List.copyOf(references);
    }

    public String adapterMailboxKey() {
        return batch.adapterMailboxKey();
    }

    public List<DispatchRoutingItem> items() {
        return batch.items();
    }
}
