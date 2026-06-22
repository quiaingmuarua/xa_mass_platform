package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runtime registry of worker transport bindings assembled for an embedded
 * XA Mass runtime.
 *
 * <p>Worker registration still resolves a concrete {@code adapterId} from the
 * requested adapter or transport hint. Assigned-delivery routing resolves the
 * local binding by {@code adapterMailboxKey}; the mailbox key is the physical
 * handoff target, while {@code adapterId} remains embedded adapter metadata.
 * Adapter-specific wire-frame shapes belong to one adapter only and must not
 * be treated as the identity of a business or control capability.
 */
public final class TransportRuntimeRegistry {

    private final TransportResultIngressChannel resultIngressChannel;
    private final TransportEndpointLeaseStore endpointLeaseStore;
    private final List<TransportBinding> bindings;
    private final TransportRegistrationResolver registrationResolver;
    private final Map<String, TransportBinding> bindingByAdapterId;
    private final Map<String, TransportBinding> bindingByAdapterMailboxKey;
    private final Map<AdapterCommandExecutor, String> adapterIdByCommandExecutor;

    public TransportRuntimeRegistry(TransportResultIngressChannel resultIngressChannel,
                                    TransportEndpointLeaseStore endpointLeaseStore,
                                    List<TransportBinding> bindings) {
        this.resultIngressChannel = Objects.requireNonNull(resultIngressChannel, "resultIngressChannel");
        this.endpointLeaseStore = Objects.requireNonNull(endpointLeaseStore, "endpointLeaseStore");
        this.bindings = List.copyOf(bindings);
        if (this.bindings.isEmpty()) {
            throw new IllegalArgumentException("At least one transport binding is required");
        }
        this.registrationResolver = TransportRegistrationResolver.fromBindings(this.bindings);
        this.bindingByAdapterId = new LinkedHashMap<>();
        this.bindingByAdapterMailboxKey = new LinkedHashMap<>();
        this.adapterIdByCommandExecutor = new IdentityHashMap<>();
        for (TransportBinding binding : this.bindings) {
            registerCommandExecutor(binding);
            registerAdapterId(binding.getAdapterId(), binding);
            registerAdapterMailboxKey(binding.getAdapterMailboxKey(), binding);
        }
    }

    public String resolveRegistrationAdapterId(String requestedAdapterId, String transportHint) {
        return registrationResolver.resolveRegistrationAdapterId(requestedAdapterId, transportHint);
    }

    public TransportBinding resolveBinding(String requestedAdapterId, String transportHint) {
        String resolvedAdapterId = registrationResolver.resolveRegistrationAdapterId(requestedAdapterId, transportHint);
        TransportBinding binding = bindingByAdapterId.get(normalizeAdapterId(resolvedAdapterId));
        if (binding == null) {
            throw new IllegalStateException("Resolved worker adapterId '" + resolvedAdapterId
                    + "' but no runtime binding is registered; available adapterIds=" + availableAdapterIds());
        }
        return binding;
    }

    public String resolveAdapterId(String requestedAdapterId, String transportHint) {
        return resolveBinding(requestedAdapterId, transportHint).getAdapterId();
    }

    public String resolveTransportHint(String requestedAdapterId, String transportHint) {
        return resolveBinding(requestedAdapterId, transportHint).getTransportHint();
    }

    public AdapterCommandExecutor resolveCommandExecutor(String requestedAdapterId, String transportHint) {
        return resolveBinding(requestedAdapterId, transportHint).getCommandExecutor();
    }

    public TransportBinding resolveBindingByAdapterId(String adapterId) {
        TransportBinding binding = bindingByAdapterId.get(normalizeAdapterId(adapterId));
        if (binding == null) {
            throw new IllegalStateException("No runtime binding is registered for adapterId '" + adapterId
                    + "'; available adapterIds=" + availableAdapterIds());
        }
        return binding;
    }

    public TransportBinding resolveBindingByAdapterMailboxKey(String adapterMailboxKey) {
        TransportBinding binding = bindingByAdapterMailboxKey.get(normalizeMailboxKey(adapterMailboxKey));
        if (binding == null) {
            throw new IllegalStateException("No runtime binding is registered for adapterMailboxKey '"
                    + adapterMailboxKey + "'; available adapterMailboxKeys=" + availableAdapterMailboxKeys());
        }
        return binding;
    }

    public ResolvedPullWorkerTransport resolvePullWorkerTransport(String workerId,
                                                                  String workerGroupId,
                                                                  String requestedAdapterId,
                                                                  String transportHint) {
        String normalizedWorkerId = requireWorkerId(workerId);
        String normalizedWorkerGroupId = requireWorkerGroupId(workerGroupId, normalizedWorkerId);
        TransportBinding binding = resolveBinding(requestedAdapterId, transportHint);
        if (binding.getDeliveryPullChannel() == null) {
            throw new IllegalStateException("Worker adapter '" + binding.getAdapterId()
                    + "' under transport '" + binding.getTransportHint()
                    + "' is not pull-capable for worker " + normalizedWorkerId);
        }
        if (binding.getPullSessionEvidenceDriver() == null) {
            throw new IllegalStateException("Worker adapter '" + binding.getAdapterId()
                    + "' under transport '" + binding.getTransportHint()
                    + "' has no pull-session evidence driver for worker " + normalizedWorkerId);
        }
        return new ResolvedPullWorkerTransport(
                normalizedWorkerId,
                normalizedWorkerGroupId,
                binding.getAdapterId(),
                binding.getTransportHint(),
                binding.getDeliveryPullChannel(),
                resultIngressChannel,
                binding.getPullSessionEvidenceDriver()
        );
    }

    private String requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        return workerId.trim();
    }

    private String requireWorkerGroupId(String workerGroupId, String workerId) {
        if (workerGroupId == null || workerGroupId.isBlank()) {
            throw new IllegalStateException("Worker workerGroupId is not set: " + workerId);
        }
        return workerGroupId.trim();
    }

    private void registerAdapterId(String adapterId, TransportBinding binding) {
        String normalized = normalizeAdapterId(adapterId);
        if (normalized != null) {
            TransportBinding existing = bindingByAdapterId.get(normalized);
            if (existing != null && existing != binding) {
                if (existing.getAdapterId().equals(binding.getAdapterId())) {
                    throw new IllegalArgumentException("Duplicate worker adapter identity '" + normalized
                            + "' is registered more than once for adapter '" + binding.getAdapterId() + "'");
                }
                throw new IllegalArgumentException("Duplicate worker adapter identity '" + normalized
                        + "' is claimed by adapters '" + existing.getAdapterId()
                        + "' and '" + binding.getAdapterId() + "'");
            }
            bindingByAdapterId.put(normalized, binding);
        }
    }

    private String availableAdapterIds() {
        Set<String> adapterIds = new TreeSet<>();
        adapterIds.addAll(bindingByAdapterId.keySet());
        return adapterIds.toString();
    }

    private String availableAdapterMailboxKeys() {
        Set<String> mailboxKeys = new TreeSet<>();
        mailboxKeys.addAll(bindingByAdapterMailboxKey.keySet());
        return mailboxKeys.toString();
    }

    private static String normalizeAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            return null;
        }
        return adapterId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void registerAdapterMailboxKey(String adapterMailboxKey, TransportBinding binding) {
        String normalized = normalizeMailboxKey(adapterMailboxKey);
        if (normalized == null) {
            throw new IllegalArgumentException("adapterMailboxKey must not be blank for adapter '"
                    + binding.getAdapterId() + "'");
        }
        TransportBinding existing = bindingByAdapterMailboxKey.get(normalized);
        if (existing != null && existing != binding) {
            throw new IllegalArgumentException("Duplicate adapter mailbox key '" + normalized
                    + "' is claimed by adapters '" + existing.getAdapterId()
                    + "' and '" + binding.getAdapterId() + "'");
        }
        bindingByAdapterMailboxKey.put(normalized, binding);
    }

    private static String normalizeMailboxKey(String adapterMailboxKey) {
        if (adapterMailboxKey == null || adapterMailboxKey.isBlank()) {
            return null;
        }
        return adapterMailboxKey.trim();
    }

    private void registerCommandExecutor(TransportBinding binding) {
        AdapterCommandExecutor executor = binding.getCommandExecutor();
        String existingAdapterId = adapterIdByCommandExecutor.get(executor);
        if (existingAdapterId != null && !existingAdapterId.equals(binding.getAdapterId())) {
            throw new IllegalArgumentException("Adapter command executor instance is shared by adapters '"
                    + existingAdapterId + "' and '" + binding.getAdapterId()
                    + "'; each adapter binding must own a distinct executor instance");
        }
        adapterIdByCommandExecutor.put(executor, binding.getAdapterId());
    }

}
