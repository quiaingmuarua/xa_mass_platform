package com.xa.mass.transport.starter;

import java.util.List;
import java.util.Objects;

/**
 * Result of embedded adapter runtime creation.
 */
public record EmbeddedAdapterCreateResult(List<String> adapterIds) {

    public EmbeddedAdapterCreateResult {
        adapterIds = List.copyOf(Objects.requireNonNull(adapterIds, "adapterIds"));
    }
}
