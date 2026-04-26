package com.xa.mass.starter.transport;

import com.xa.mass.transport.WorkerTransportHints;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Adapter-id resolution for worker registration input.
 *
 * <p>This keeps the adapter identity rules reusable across the live runtime
 * registry and pre-start embedded-runtime composition.
 */
public final class TransportRegistrationResolver {

    private final Map<String, TransportAdapterDescriptor> descriptorByAdapterId;
    private final Map<String, List<TransportAdapterDescriptor>> descriptorsByTransportHint;

    public TransportRegistrationResolver(List<TransportAdapterDescriptor> descriptors) {
        this.descriptorByAdapterId = new LinkedHashMap<>();
        this.descriptorsByTransportHint = new LinkedHashMap<>();
        for (TransportAdapterDescriptor descriptor : List.copyOf(descriptors)) {
            registerAdapterId(descriptor.getAdapterId(), descriptor);
            for (String alias : descriptor.getAliases()) {
                registerAdapterId(alias, descriptor);
            }
            registerTransportHint(descriptor.getTransportHint(), descriptor);
        }
    }

    public static TransportRegistrationResolver fromBindings(List<TransportBinding> bindings) {
        List<TransportAdapterDescriptor> descriptors = bindings.stream()
                .map(TransportAdapterDescriptor::fromBinding)
                .toList();
        return new TransportRegistrationResolver(descriptors);
    }

    public boolean supportsAdapterId(String adapterId) {
        return descriptorByAdapterId.containsKey(normalizeAdapterId(adapterId));
    }

    public String resolveRegistrationAdapterId(String requestedAdapterId, String transportHint) {
        String normalizedTransportHint = WorkerTransportHints.normalize(transportHint);
        if (requestedAdapterId != null && !requestedAdapterId.isBlank()) {
            String normalizedAdapterId = normalizeAdapterId(requestedAdapterId);
            TransportAdapterDescriptor descriptor = descriptorByAdapterId.get(normalizedAdapterId);
            if (descriptor == null) {
                throw new IllegalArgumentException("Unsupported worker adapterId '" + normalizedAdapterId
                        + "'; available adapterIds=" + availableAdapterIds());
            }
            if (normalizedTransportHint != null && !normalizedTransportHint.equals(descriptor.getTransportHint())) {
                throw new IllegalArgumentException("Worker adapterId '" + normalizedAdapterId
                        + "' belongs to transportHint '" + descriptor.getTransportHint()
                        + "', not '" + normalizedTransportHint + "'");
            }
            return descriptor.getAdapterId();
        }
        if (normalizedTransportHint == null) {
            throw new IllegalArgumentException("transportHint must not be blank");
        }
        List<TransportAdapterDescriptor> familyDescriptors = descriptorsByTransportHint.get(normalizedTransportHint);
        if (familyDescriptors == null || familyDescriptors.isEmpty()) {
            throw new IllegalArgumentException("Unsupported worker transportHint '" + normalizedTransportHint
                    + "'; available transportHints=" + availableTransportHints());
        }
        if (familyDescriptors.size() > 1) {
            throw new IllegalArgumentException("worker adapterId must be set when transportHint '"
                    + normalizedTransportHint + "' matches multiple adapters " + adapterIds(familyDescriptors));
        }
        return familyDescriptors.get(0).getAdapterId();
    }

    private void registerAdapterId(String adapterId, TransportAdapterDescriptor descriptor) {
        String normalized = normalizeAdapterId(adapterId);
        if (normalized != null) {
            TransportAdapterDescriptor existing = descriptorByAdapterId.get(normalized);
            if (existing != null && existing != descriptor) {
                if (existing.getAdapterId().equals(descriptor.getAdapterId())) {
                    throw new IllegalArgumentException("Duplicate worker adapter identity '" + normalized
                            + "' is registered more than once for adapter '" + descriptor.getAdapterId() + "'");
                }
                throw new IllegalArgumentException("Duplicate worker adapter identity '" + normalized
                        + "' is claimed by adapters '" + existing.getAdapterId()
                        + "' and '" + descriptor.getAdapterId() + "'");
            }
            descriptorByAdapterId.put(normalized, descriptor);
        }
    }

    private void registerTransportHint(String hint, TransportAdapterDescriptor descriptor) {
        String normalized = WorkerTransportHints.normalize(hint);
        if (normalized == null) {
            return;
        }
        List<TransportAdapterDescriptor> existing =
                new ArrayList<>(descriptorsByTransportHint.getOrDefault(normalized, List.of()));
        if (!existing.contains(descriptor)) {
            existing.add(descriptor);
        }
        descriptorsByTransportHint.put(normalized, List.copyOf(existing));
    }

    private String availableAdapterIds() {
        Set<String> adapterIds = new TreeSet<>(descriptorByAdapterId.keySet());
        return adapterIds.toString();
    }

    private String availableTransportHints() {
        List<String> hints = new ArrayList<>(descriptorsByTransportHint.keySet());
        Collections.sort(hints);
        return hints.toString();
    }

    private static String adapterIds(List<TransportAdapterDescriptor> descriptors) {
        List<String> adapterIds = descriptors.stream()
                .map(TransportAdapterDescriptor::getAdapterId)
                .distinct()
                .sorted()
                .toList();
        return adapterIds.toString();
    }

    private static String normalizeAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            return null;
        }
        return adapterId.trim().toLowerCase(Locale.ROOT);
    }
}
