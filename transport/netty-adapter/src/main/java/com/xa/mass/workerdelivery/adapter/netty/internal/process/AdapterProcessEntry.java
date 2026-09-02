package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import java.util.Objects;

/** Immutable lifecycle metadata for one resident Adapter process. */
public record AdapterProcessEntry(
        String processId,
        QuiescePhase quiescePhase,
        AdapterProcess process
) {

    public AdapterProcessEntry {
        if (processId == null || processId.isBlank()) {
            throw new IllegalArgumentException(
                    "processId must be non-blank"
            );
        }
        quiescePhase = Objects.requireNonNull(
                quiescePhase,
                "quiescePhase"
        );
        process = Objects.requireNonNull(process, "process");
    }
}
