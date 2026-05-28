package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import com.xa.mass.worker.runtime.resource.WorkerResourceQueryRuntime;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.presence.WorkerPresenceStore;
import com.xa.mass.transport.runtime.worker.TransportRoutingTaskDispatchListener;
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

    private final WorkerResourceQueryRuntime workerResourceRuntime;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;
    private final WorkerPresenceStore workerPresenceStore;
    private final List<TransportBinding> bindings;
    private final TransportRegistrationResolver registrationResolver;
    private final Map<String, TransportBinding> bindingByAdapterId;

    public TransportRuntimeRegistry(WorkerResourceQueryRuntime workerResourceRuntime,
                                    TaskResultIngestChannel taskResultIngestChannel,
                                    WorkerSystemEventChannel systemEventChannel,
                                    WorkerPresenceStore workerPresenceStore,
                                    List<TransportBinding> bindings) {
        this.workerResourceRuntime = Objects.requireNonNull(workerResourceRuntime, "workerResourceRuntime");
        this.taskResultIngestChannel = Objects.requireNonNull(taskResultIngestChannel, "taskResultIngestChannel");
        this.systemEventChannel = Objects.requireNonNull(systemEventChannel, "systemEventChannel");
        this.workerPresenceStore = Objects.requireNonNull(workerPresenceStore, "workerPresenceStore");
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

    public TaskDispatchBatchListener createDispatchBatchListener() {
        return createDispatchBatchListener(null);
    }

    public TaskDispatchBatchListener createDispatchBatchListener(TransportDispatchFailureHandler failureHandler) {
        return createDispatchBatchListener(failureHandler, null);
    }

    public TaskDispatchBatchListener createDispatchBatchListener(TransportDispatchFailureHandler failureHandler,
                                                                RuntimeTaskExecutor runtimeTaskExecutor) {
        return new TransportRoutingTaskDispatchListener(
                workerResourceRuntime,
                this,
                failureHandler,
                runtimeTaskExecutor
        );
    }

    public String resolveRegistrationAdapterId(String requestedAdapterId, String transportHint) {
        return registrationResolver.resolveRegistrationAdapterId(requestedAdapterId, transportHint);
    }

    public String resolveWorkerAdapterId(String workerId) {
        return resolveBindingForWorker(requireWorker(workerId)).getAdapterId();
    }

    public String resolveWorkerTransportHint(String workerId) {
        return resolveBindingForWorker(requireWorker(workerId)).getTransportHint();
    }

    public WorkerAdapter resolveDispatchAdapter(String workerId) {
        return resolveBindingForWorker(requireWorker(workerId)).getWorkerAdapter();
    }

    public TransportBinding resolveDispatchBinding(String workerId) {
        return resolveBindingForWorker(requireWorker(workerId));
    }

    public TransportBinding resolveDispatchBinding(WorkerResourceRecord worker) {
        return resolveBindingForWorker(Objects.requireNonNull(worker, "worker"));
    }

    public ResolvedPullWorkerTransport resolvePullWorkerTransport(String workerId) {
        String normalizedWorkerId = requireWorkerId(workerId);
        WorkerResourceRecord worker = requireWorker(normalizedWorkerId);
        TransportBinding binding = resolveBindingForWorker(worker);
        if (binding.getTaskPullChannel() == null) {
            throw new IllegalStateException("Worker adapter '" + binding.getAdapterId()
                    + "' under transport '" + binding.getTransportHint()
                    + "' is not pull-capable for worker " + normalizedWorkerId);
        }
        return new ResolvedPullWorkerTransport(
                normalizedWorkerId,
                binding.getAdapterId(),
                binding.getTransportHint(),
                binding.getTaskPullChannel(),
                taskResultIngestChannel,
                systemEventChannel,
                workerPresenceStore
        );
    }

    public WorkerPresenceStore getWorkerPresenceStore() {
        return workerPresenceStore;
    }

    private WorkerResourceRecord requireWorker(String workerId) {
        String normalizedWorkerId = requireWorkerId(workerId);
        WorkerResourceRecord worker = workerResourceRuntime.worker(normalizedWorkerId).orElse(null);
        if (worker == null) {
            throw new IllegalArgumentException("Worker not found: " + requireWorkerId(workerId));
        }
        return worker;
    }

    private String requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        return workerId.trim();
    }

    private TransportBinding resolveBindingForWorker(WorkerResourceRecord worker) {
        String workerId = worker.workerId();
        String resolvedAdapterId;
        try {
            resolvedAdapterId = registrationResolver.resolveRegistrationAdapterId(
                    worker.adapterId(),
                    worker.onlineStrategy()
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Cannot resolve transport binding for worker " + workerId
                    + ": " + e.getMessage(), e);
        }
        TransportBinding binding = bindingByAdapterId.get(normalizeAdapterId(resolvedAdapterId));
        if (binding == null) {
            throw new IllegalStateException("Resolved worker adapterId '" + resolvedAdapterId
                    + "' for worker " + workerId + " but no runtime binding is registered; available adapterIds="
                    + availableAdapterIds());
        }
        return binding;
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
}
