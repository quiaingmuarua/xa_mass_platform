package com.xa.mass.starter.transport;

import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.base.model.Worker;
import com.xa.mass.starter.worker.TransportRoutingTaskMsgDispatchListener;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

import java.util.ArrayList;
import java.util.Collections;
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
 * <p>Inbound compatibility registration in this registry remains
 * protocol-oriented. Adapter-specific wire-frame shapes belong to a specific adapter only and
 * must not be treated as the identity of a business or control capability.
 */
public final class TransportRuntimeRegistry {

    private final WorkerManager workerManager;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;
    private final List<TransportBinding> bindings;
    private final Map<String, TransportBinding> bindingByAdapterId;
    private final Map<String, List<TransportBinding>> bindingsByTransportHint;

    public TransportRuntimeRegistry(WorkerManager workerManager,
                                    TaskResultIngestChannel taskResultIngestChannel,
                                    WorkerSystemEventChannel systemEventChannel,
                                    List<TransportBinding> bindings) {
        this.workerManager = Objects.requireNonNull(workerManager, "workerManager");
        this.taskResultIngestChannel = Objects.requireNonNull(taskResultIngestChannel, "taskResultIngestChannel");
        this.systemEventChannel = Objects.requireNonNull(systemEventChannel, "systemEventChannel");
        this.bindings = List.copyOf(bindings);
        if (this.bindings.isEmpty()) {
            throw new IllegalArgumentException("At least one transport binding is required");
        }
        this.bindingByAdapterId = new LinkedHashMap<>();
        this.bindingsByTransportHint = new LinkedHashMap<>();
        for (TransportBinding binding : this.bindings) {
            registerAdapterId(binding.getAdapterId(), binding);
            for (String alias : binding.getWorkerAdapter().aliases()) {
                registerAdapterId(alias, binding);
            }
            registerTransportHint(binding.getTransportHint(), binding);
        }
    }

    public TaskMsgDispatchListener createDispatchListener() {
        return new TransportRoutingTaskMsgDispatchListener(workerManager, this);
    }

    public String resolveRegistrationAdapterId(String requestedAdapterId, String transportHint) {
        String normalizedTransportHint = WorkerTransportHints.normalize(transportHint);
        if (requestedAdapterId != null && !requestedAdapterId.isBlank()) {
            String normalizedAdapterId = normalizeAdapterId(requestedAdapterId);
            TransportBinding binding = bindingByAdapterId.get(normalizedAdapterId);
            if (binding == null) {
                throw new IllegalArgumentException("Unsupported worker adapterId '" + normalizedAdapterId
                        + "'; available adapterIds=" + availableAdapterIds());
            }
            if (normalizedTransportHint != null && !normalizedTransportHint.equals(binding.getTransportHint())) {
                throw new IllegalArgumentException("Worker adapterId '" + normalizedAdapterId
                        + "' belongs to transportHint '" + binding.getTransportHint()
                        + "', not '" + normalizedTransportHint + "'");
            }
            return binding.getAdapterId();
        }
        if (normalizedTransportHint == null) {
            throw new IllegalArgumentException("transportHint must not be blank");
        }
        List<TransportBinding> familyBindings = bindingsByTransportHint.get(normalizedTransportHint);
        if (familyBindings == null || familyBindings.isEmpty()) {
            throw new IllegalArgumentException("Unsupported worker transportHint '" + normalizedTransportHint
                    + "'; available transportHints=" + availableTransportHints());
        }
        if (familyBindings.size() > 1) {
            throw new IllegalArgumentException("worker adapterId must be set when transportHint '"
                    + normalizedTransportHint + "' matches multiple adapters " + adapterIds(familyBindings));
        }
        return familyBindings.get(0).getAdapterId();
    }

    public String resolveWorkerAdapterId(String workerId) {
        return resolveBindingForWorker(requireWorker(workerId)).getAdapterId();
    }

    public WorkerAdapter resolveDispatchAdapter(String workerId) {
        return resolveBindingForWorker(requireWorker(workerId)).getWorkerAdapter();
    }

    public ResolvedPullWorkerTransport resolvePullWorkerTransport(String workerId) {
        String normalizedWorkerId = requireWorkerId(workerId);
        Worker worker = requireWorker(normalizedWorkerId);
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
                systemEventChannel
        );
    }

    private Worker requireWorker(String workerId) {
        Worker worker = workerManager.getWorker(requireWorkerId(workerId));
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

    private TransportBinding resolveBindingForWorker(Worker worker) {
        String workerId = worker.getWorkerId();
        String requestedAdapterId = normalizeAdapterId(worker.getAdapterId());
        if (requestedAdapterId != null) {
            TransportBinding binding = bindingByAdapterId.get(requestedAdapterId);
            if (binding != null) {
                return binding;
            }
            throw new IllegalStateException("Unsupported worker adapterId '" + requestedAdapterId
                    + "' for worker " + workerId + "; available adapterIds=" + availableAdapterIds());
        }

        String transportHint = WorkerTransportHints.normalize(worker.getOnlineStrategy());
        if (transportHint == null) {
            throw new IllegalStateException("Worker adapterId is not set and transportHint/onlineStrategy is not set: "
                    + workerId);
        }
        List<TransportBinding> familyBindings = bindingsByTransportHint.get(transportHint);
        if (familyBindings == null || familyBindings.isEmpty()) {
            throw new IllegalStateException("Unsupported worker transport '" + transportHint
                    + "' for worker " + workerId + "; available transports=" + availableTransportHints());
        }
        if (familyBindings.size() > 1) {
            throw new IllegalStateException("Worker adapterId must be set for worker " + workerId
                    + " because transport '" + transportHint + "' matches multiple adapters "
                    + adapterIds(familyBindings));
        }
        return familyBindings.get(0);
    }

    private void registerAdapterId(String adapterId, TransportBinding binding) {
        String normalized = normalizeAdapterId(adapterId);
        if (normalized != null) {
            bindingByAdapterId.put(normalized, binding);
        }
    }

    private void registerTransportHint(String hint, TransportBinding binding) {
        String normalized = WorkerTransportHints.normalize(hint);
        if (normalized == null) {
            return;
        }
        List<TransportBinding> existing = new ArrayList<>(bindingsByTransportHint.getOrDefault(normalized, List.of()));
        if (!existing.contains(binding)) {
            existing.add(binding);
        }
        bindingsByTransportHint.put(normalized, List.copyOf(existing));
    }

    private String availableAdapterIds() {
        Set<String> adapterIds = new TreeSet<>();
        adapterIds.addAll(bindingByAdapterId.keySet());
        return adapterIds.toString();
    }

    private String availableTransportHints() {
        List<String> hints = new ArrayList<>(bindingsByTransportHint.keySet());
        Collections.sort(hints);
        return hints.toString();
    }

    private static String adapterIds(List<TransportBinding> bindings) {
        List<String> adapterIds = bindings.stream()
                .map(TransportBinding::getAdapterId)
                .distinct()
                .sorted()
                .toList();
        return adapterIds.toString();
    }

    private static String normalizeAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            return null;
        }
        return adapterId.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
