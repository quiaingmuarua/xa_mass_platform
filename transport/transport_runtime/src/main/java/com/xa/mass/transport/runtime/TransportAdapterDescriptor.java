package com.xa.mass.transport.runtime;

import com.xa.mass.transport.WorkerTransportHints;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Identity metadata for one concrete worker transport adapter.
 */
public final class TransportAdapterDescriptor {

    private final String adapterId;
    private final String transportHint;
    private final Set<String> aliases;

    public TransportAdapterDescriptor(String adapterId, String transportHint, Set<String> aliases) {
        this.adapterId = normalizeAdapterId(Objects.requireNonNull(adapterId, "adapterId"));
        this.transportHint = WorkerTransportHints.normalize(Objects.requireNonNull(transportHint, "transportHint"));
        if (this.transportHint == null) {
            throw new IllegalArgumentException("transportHint must normalize to a non-blank value");
        }
        Set<String> normalizedAliases = new LinkedHashSet<>();
        if (aliases != null) {
            for (String alias : aliases) {
                String normalizedAlias = normalizeAdapterId(alias);
                if (normalizedAlias != null && !normalizedAlias.equals(this.adapterId)) {
                    normalizedAliases.add(normalizedAlias);
                }
            }
        }
        this.aliases = Set.copyOf(normalizedAliases);
    }

    public static TransportAdapterDescriptor fromBinding(TransportBinding binding) {
        Objects.requireNonNull(binding, "binding");
        return new TransportAdapterDescriptor(
                binding.getAdapterId(),
                binding.getTransportHint(),
                binding.getWorkerAdapter().aliases()
        );
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getTransportHint() {
        return transportHint;
    }

    public Set<String> getAliases() {
        return aliases;
    }

    private static String normalizeAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            return null;
        }
        return adapterId.trim().toLowerCase(Locale.ROOT);
    }
}
