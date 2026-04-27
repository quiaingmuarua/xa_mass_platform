package com.xa.mass.transport.runtime;

import com.xa.mass.transport.WorkerTransportHints;

import java.util.Locale;
import java.util.Objects;

/**
 * Identity metadata for one concrete worker transport adapter.
 */
public final class TransportAdapterDescriptor {

    private final String adapterId;
    private final String transportHint;

    public TransportAdapterDescriptor(String adapterId, String transportHint) {
        this.adapterId = normalizeAdapterId(Objects.requireNonNull(adapterId, "adapterId"));
        this.transportHint = WorkerTransportHints.normalize(Objects.requireNonNull(transportHint, "transportHint"));
        if (this.transportHint == null) {
            throw new IllegalArgumentException("transportHint must normalize to a non-blank value");
        }
    }

    public static TransportAdapterDescriptor fromBinding(TransportBinding binding) {
        Objects.requireNonNull(binding, "binding");
        return new TransportAdapterDescriptor(
                binding.getAdapterId(),
                binding.getTransportHint()
        );
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getTransportHint() {
        return transportHint;
    }

    private static String normalizeAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            return null;
        }
        return adapterId.trim().toLowerCase(Locale.ROOT);
    }
}
