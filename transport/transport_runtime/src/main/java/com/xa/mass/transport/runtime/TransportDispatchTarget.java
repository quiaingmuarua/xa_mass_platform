package com.xa.mass.transport.runtime;

import com.xa.mass.transport.worker.WorkerAdapter;

import java.util.Objects;

/**
 * Already resolved transport delivery target for one assigned dispatch binding.
 */
public record TransportDispatchTarget(WorkerAdapter adapter, String adapterId, String routeKey) {

    public TransportDispatchTarget {
        adapter = Objects.requireNonNull(adapter, "adapter");
        adapterId = requireText(adapterId, "adapterId");
        routeKey = requireText(routeKey, "routeKey");
    }

    public static TransportDispatchTarget of(TransportBinding binding, String routeKey) {
        Objects.requireNonNull(binding, "binding");
        return new TransportDispatchTarget(binding.getWorkerAdapter(), binding.getAdapterId(), routeKey);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
