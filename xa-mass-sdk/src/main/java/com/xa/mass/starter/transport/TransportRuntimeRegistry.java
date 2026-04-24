package com.xa.mass.starter.transport;

import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.base.model.Worker;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.starter.worker.TransportRoutingTaskMsgDispatchListener;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime registry of worker transport bindings assembled for an embedded
 * XA Mass runtime.
 *
 * <p>Inbound route registration in this registry remains protocol-oriented.
 * Route tuples classify wire frames for a specific adapter and must not be
 * treated as the identity of a business or control capability.
 */
public final class TransportRuntimeRegistry {

    private final WorkerManager workerManager;
    private final WorkerSystemEventChannel systemEventChannel;
    private final List<TransportBinding> bindings;
    private final Map<String, TransportBinding> bindingByHint;

    public TransportRuntimeRegistry(WorkerManager workerManager,
                                    WorkerSystemEventChannel systemEventChannel,
                                    List<TransportBinding> bindings) {
        this.workerManager = Objects.requireNonNull(workerManager, "workerManager");
        this.systemEventChannel = Objects.requireNonNull(systemEventChannel, "systemEventChannel");
        this.bindings = List.copyOf(bindings);
        if (this.bindings.isEmpty()) {
            throw new IllegalArgumentException("At least one transport binding is required");
        }
        this.bindingByHint = new LinkedHashMap<>();
        for (TransportBinding binding : this.bindings) {
            registerBinding(binding.getTransportHint(), binding);
            for (String alias : binding.getWorkerAdapter().aliases()) {
                registerBinding(alias, binding);
            }
        }
    }

    public com.xa.mass.gateway.dispatcher.port.TaskStepFrameBridge resolveTaskStepFrameBridge() {
        com.xa.mass.gateway.dispatcher.port.TaskStepFrameBridge resolvedBridge = null;
        for (TransportBinding binding : bindings) {
            if (binding.getTaskStepFrameBridge() != null) {
                if (resolvedBridge != null) {
                    throw new IllegalStateException("Multiple task-step frame bridges are configured for the gateway runtime");
                }
                resolvedBridge = binding.getTaskStepFrameBridge();
            }
        }
        return resolvedBridge;
    }

    public TaskMsgDispatchListener createDispatchListener() {
        List<WorkerAdapter> workerAdapters = bindings.stream()
                .map(TransportBinding::getWorkerAdapter)
                .toList();
        return new TransportRoutingTaskMsgDispatchListener(workerManager, workerAdapters);
    }

    public PullWorkerSession openPullWorkerSession(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        String normalizedWorkerId = workerId.trim();
        Worker worker = workerManager.getWorker(normalizedWorkerId);
        if (worker == null) {
            throw new IllegalArgumentException("Worker not found: " + normalizedWorkerId);
        }
        String transportHint = WorkerTransportHints.normalize(worker.getOnlineStrategy());
        if (transportHint == null) {
            throw new IllegalStateException("Worker transportHint/onlineStrategy is not set: " + normalizedWorkerId);
        }

        TransportBinding binding = bindingByHint.get(transportHint);
        if (binding == null) {
            throw new IllegalStateException("No transport binding is registered for worker transport '"
                    + transportHint + "' on worker " + normalizedWorkerId);
        }
        if (binding.getTaskPullChannel() == null || binding.getTaskResultIngestChannel() == null) {
            throw new IllegalStateException("Worker transport '" + transportHint
                    + "' is not pull-capable for worker " + normalizedWorkerId);
        }
        return new PullWorkerSession(
                normalizedWorkerId,
                binding.getTaskPullChannel(),
                binding.getTaskResultIngestChannel(),
                systemEventChannel,
                transportHint
        );
    }

    public WorkerControlEventPublishResult publishWorkerControlEvent(WorkerControlEventDispatch request) {
        Objects.requireNonNull(request, "request");
        Worker worker = workerManager.getWorker(request.getWorkerId());
        if (worker == null) {
            throw new IllegalArgumentException("Worker not found: " + request.getWorkerId());
        }
        String transportHint = WorkerTransportHints.normalize(worker.getOnlineStrategy());
        if (transportHint == null) {
            throw new IllegalStateException("Worker transportHint/onlineStrategy is not set: " + request.getWorkerId());
        }
        TransportBinding binding = bindingByHint.get(transportHint);
        if (binding == null) {
            throw new IllegalStateException("No transport binding is registered for worker transport '"
                    + transportHint + "' on worker " + request.getWorkerId());
        }
        if (binding.getWorkerControlEventPublisher() == null) {
            throw new IllegalStateException("Worker transport '" + transportHint
                    + "' does not support outbound control-event dispatch for worker " + request.getWorkerId());
        }
        return binding.getWorkerControlEventPublisher().publish(request);
    }

    private void registerBinding(String hint, TransportBinding binding) {
        String normalized = WorkerTransportHints.normalize(hint);
        if (normalized != null) {
            bindingByHint.put(normalized, binding);
        }
    }
}
