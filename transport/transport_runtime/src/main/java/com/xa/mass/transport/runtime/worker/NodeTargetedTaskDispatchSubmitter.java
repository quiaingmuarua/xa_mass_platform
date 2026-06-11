package com.xa.mass.transport.runtime.worker;

import com.xa.mass.base.runtime.dispatch.NodeTargetedTaskDispatchHandoff;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatch;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.runtime.TransportDispatchFailureHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Engine-side submitter that routes post-claim dispatch bindings into the
 * inbox owned by the worker's current transport node.
 */
public final class NodeTargetedTaskDispatchSubmitter implements TaskDispatchBatchListener {

    private static final Logger logger = LoggerFactory.getLogger(NodeTargetedTaskDispatchSubmitter.class);

    private final NodeTargetedTaskDispatchHandoff handoff;
    private final Function<TaskDispatchBinding, Optional<String>> transportNodeResolver;
    private final TransportDispatchFailureHandler failureHandler;

    public NodeTargetedTaskDispatchSubmitter(NodeTargetedTaskDispatchHandoff handoff,
                                             Function<TaskDispatchBinding, Optional<String>> transportNodeResolver,
                                             TransportDispatchFailureHandler failureHandler) {
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.transportNodeResolver = Objects.requireNonNull(transportNodeResolver, "transportNodeResolver");
        this.failureHandler = failureHandler;
    }

    @Override
    public void onTaskDispatchBatch(TaskDispatchContext task, List<TaskDispatchBinding> dispatchBindings) {
        if (task == null || dispatchBindings == null || dispatchBindings.isEmpty()) {
            return;
        }

        Map<String, List<TaskDispatchBinding>> bindingsByNode = new LinkedHashMap<>();
        List<TaskDispatchBinding> unresolved = new ArrayList<>();
        for (TaskDispatchBinding binding : dispatchBindings) {
            if (binding == null) {
                continue;
            }
            Optional<String> transportNodeId = transportNodeResolver.apply(binding)
                    .map(String::trim)
                    .filter(value -> !value.isBlank());
            if (transportNodeId.isEmpty()) {
                unresolved.add(binding);
                continue;
            }
            bindingsByNode.computeIfAbsent(transportNodeId.get(), ignored -> new ArrayList<>()).add(binding);
        }

        compensate(task, unresolved, "transport route owner is unavailable after assignment");

        for (Map.Entry<String, List<TaskDispatchBinding>> entry : bindingsByNode.entrySet()) {
            String transportNodeId = entry.getKey();
            List<TaskDispatchBinding> nodeBindings = List.copyOf(entry.getValue());
            try {
                handoff.submit(transportNodeId, new TaskDispatchBatch(task, nodeBindings));
            } catch (RuntimeException e) {
                logger.warn("Failed to submit dispatch batch to transport node inbox: taskId={}, transportNodeId={}, bindings={}, reason={}",
                        task.taskId(), transportNodeId, nodeBindings.size(), e.getMessage());
                compensate(task, nodeBindings, "transport node dispatch inbox submit failed: " + e.getMessage());
            }
        }
    }

    private void compensate(TaskDispatchContext task, List<TaskDispatchBinding> dispatchBindings, String detail) {
        if (dispatchBindings == null || dispatchBindings.isEmpty()) {
            return;
        }
        if (failureHandler == null) {
            logger.warn("Cannot compensate node-targeted dispatch failure because no failure handler is configured: taskId={}, bindings={}, detail={}",
                    task != null ? task.taskId() : null, dispatchBindings.size(), detail);
            return;
        }
        boolean compensated = failureHandler.compensate(task, List.copyOf(dispatchBindings), detail);
        if (!compensated) {
            logger.error("Node-targeted dispatch failure was not compensated: taskId={}, bindings={}, detail={}",
                    task != null ? task.taskId() : null, dispatchBindings.size(), detail);
        }
    }
}
