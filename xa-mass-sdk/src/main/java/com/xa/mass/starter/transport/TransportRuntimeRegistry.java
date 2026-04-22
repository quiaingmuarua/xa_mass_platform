package com.xa.mass.starter.transport;

import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.gateway.dispatcher.MessageHandlerRegistry;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.starter.worker.TransportRoutingTaskMsgDispatchListener;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime registry of worker transport bindings assembled for an embedded
 * XA Mass runtime.
 */
public final class TransportRuntimeRegistry {

    private final WorkerManager workerManager;
    private final WorkerSystemEventChannel systemEventChannel;
    private final List<TransportBinding> bindings;
    private final Map<String, TransportBinding> bindingByHint;
    private final String defaultDispatchProtocol;
    private final String defaultPullProtocol;

    public TransportRuntimeRegistry(WorkerManager workerManager,
                                    WorkerSystemEventChannel systemEventChannel,
                                    List<TransportBinding> bindings,
                                    String defaultDispatchProtocol,
                                    String defaultPullProtocol) {
        this.workerManager = Objects.requireNonNull(workerManager, "workerManager");
        this.systemEventChannel = Objects.requireNonNull(systemEventChannel, "systemEventChannel");
        this.bindings = List.copyOf(bindings);
        if (this.bindings.isEmpty()) {
            throw new IllegalArgumentException("At least one transport binding is required");
        }
        this.bindingByHint = new LinkedHashMap<>();
        for (TransportBinding binding : this.bindings) {
            registerBinding(binding.getWorkerAdapter().protocol(), binding);
            for (String alias : binding.getWorkerAdapter().aliases()) {
                registerBinding(alias, binding);
            }
        }
        this.defaultDispatchProtocol = WorkerTransportHints.normalize(defaultDispatchProtocol);
        this.defaultPullProtocol = WorkerTransportHints.normalize(defaultPullProtocol);
    }

    public void registerInboundHandlers(MessageHandlerRegistry registry) {
        for (TransportBinding binding : bindings) {
            for (TransportInboundRoute route : binding.getInboundRoutes()) {
                registry.register(route.project(), route.messageType(), route.subMsgType(), route.handler());
            }
        }
    }

    public TaskMsgDispatchListener createDispatchListener() {
        List<WorkerAdapter> workerAdapters = bindings.stream()
                .map(TransportBinding::getWorkerAdapter)
                .toList();
        return workerAdapters.size() == 1
                ? workerAdapters.get(0)
                : new TransportRoutingTaskMsgDispatchListener(workerManager, workerAdapters, defaultDispatchProtocol);
    }

    public PullWorkerSession openPullWorkerSession(String workerId) {
        TransportBinding binding = resolvePullBinding(defaultPullProtocol);
        return new PullWorkerSession(
                workerId,
                Objects.requireNonNull(binding.getTaskPullChannel(), "taskPullChannel"),
                Objects.requireNonNull(binding.getTaskResultIngestChannel(), "taskResultIngestChannel"),
                systemEventChannel,
                binding.getWorkerAdapter().protocol()
        );
    }

    private TransportBinding resolvePullBinding(String preferredHint) {
        if (preferredHint != null) {
            TransportBinding candidate = bindingByHint.get(preferredHint);
            if (candidate != null && candidate.getTaskPullChannel() != null && candidate.getTaskResultIngestChannel() != null) {
                return candidate;
            }
        }
        for (TransportBinding binding : bindings) {
            if (binding.getTaskPullChannel() != null && binding.getTaskResultIngestChannel() != null) {
                return binding;
            }
        }
        throw new IllegalStateException("No pull-capable worker transport is available for this runtime");
    }

    private void registerBinding(String hint, TransportBinding binding) {
        String normalized = WorkerTransportHints.normalize(hint);
        if (normalized != null) {
            bindingByHint.put(normalized, binding);
        }
    }
}
