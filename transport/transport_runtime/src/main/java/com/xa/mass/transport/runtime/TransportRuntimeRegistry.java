package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.NoopDeliveryCommandConsumerRegistry;
import com.xa.mass.transport.worker.WorkerAdapter;

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
 * <p>Registration and runtime routing key off canonical worker transport
 * identity: {@code adapterId} is the concrete runtime truth, while
 * {@code transportHint} remains only the coarse transport family. Adapter-
 * specific wire-frame shapes belong to one adapter only and must not be
 * treated as the identity of a business or control capability.
 */
public final class TransportRuntimeRegistry {

    private final TaskResultIngestChannel taskResultIngestChannel;
    private final TransportEndpointLeaseStore endpointLeaseStore;
    private final DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry;
    private final String deliveryCommandConsumerKey;
    private final List<TransportBinding> bindings;
    private final TransportRegistrationResolver registrationResolver;
    private final Map<String, TransportBinding> bindingByAdapterId;

    public TransportRuntimeRegistry(TaskResultIngestChannel taskResultIngestChannel,
                                    TransportEndpointLeaseStore endpointLeaseStore,
                                    List<TransportBinding> bindings) {
        this(taskResultIngestChannel,
                endpointLeaseStore,
                NoopDeliveryCommandConsumerRegistry.INSTANCE,
                "local",
                bindings);
    }

    public TransportRuntimeRegistry(TaskResultIngestChannel taskResultIngestChannel,
                                    TransportEndpointLeaseStore endpointLeaseStore,
                                    DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry,
                                    String deliveryCommandConsumerKey,
                                    List<TransportBinding> bindings) {
        this.taskResultIngestChannel = Objects.requireNonNull(taskResultIngestChannel, "taskResultIngestChannel");
        this.endpointLeaseStore = Objects.requireNonNull(endpointLeaseStore, "endpointLeaseStore");
        this.deliveryCommandConsumerRegistry = deliveryCommandConsumerRegistry != null
                ? deliveryCommandConsumerRegistry
                : NoopDeliveryCommandConsumerRegistry.INSTANCE;
        this.deliveryCommandConsumerKey = requireText(deliveryCommandConsumerKey, "deliveryCommandConsumerKey");
        this.bindings = List.copyOf(bindings);
        if (this.bindings.isEmpty()) {
            throw new IllegalArgumentException("At least one transport binding is required");
        }
        this.registrationResolver = TransportRegistrationResolver.fromBindings(this.bindings);
        this.bindingByAdapterId = new LinkedHashMap<>();
        for (TransportBinding binding : this.bindings) {
            registerAdapterId(binding.getAdapterId(), binding);
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

    public WorkerAdapter resolveDispatchAdapter(String requestedAdapterId, String transportHint) {
        return resolveBinding(requestedAdapterId, transportHint).getWorkerAdapter();
    }

    public WorkerAdapter resolveDispatchAdapterByAdapterId(String adapterId) {
        TransportBinding binding = bindingByAdapterId.get(normalizeAdapterId(adapterId));
        if (binding == null) {
            throw new IllegalStateException("No runtime binding is registered for adapterId '" + adapterId
                    + "'; available adapterIds=" + availableAdapterIds());
        }
        return binding.getWorkerAdapter();
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
        return new ResolvedPullWorkerTransport(
                normalizedWorkerId,
                normalizedWorkerGroupId,
                binding.getAdapterId(),
                binding.getTransportHint(),
                binding.getDeliveryPullChannel(),
                taskResultIngestChannel,
                endpointLeaseStore,
                deliveryCommandConsumerRegistry,
                deliveryCommandConsumerKey
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

    private static String normalizeAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            return null;
        }
        return adapterId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
