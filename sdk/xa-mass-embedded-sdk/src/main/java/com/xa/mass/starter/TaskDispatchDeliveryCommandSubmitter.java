package com.xa.mass.starter;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.runtime.delivery.TransportAssignedDeliverySubmitter;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureHandler;
import com.xa.mass.transport.runtime.packet.TransportPacketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Starter-owned translator from engine assignment bindings to transport
 * delivery commands.
 */
final class TaskDispatchDeliveryCommandSubmitter implements TaskDispatchBatchListener {

    private static final Logger logger = LoggerFactory.getLogger(TaskDispatchDeliveryCommandSubmitter.class);

    private final TransportAssignedDeliverySubmitter assignedDeliverySubmitter;
    private final BiFunction<TaskDispatchContext, TaskDispatchBinding, String> routeKeyFactory;
    private final TransportDeliveryFailureHandler failureHandler;
    private final TransportPacketFactory packetFactory = new TransportPacketFactory();

    TaskDispatchDeliveryCommandSubmitter(TransportAssignedDeliverySubmitter assignedDeliverySubmitter,
                                         BiFunction<TaskDispatchContext, TaskDispatchBinding, String> routeKeyFactory,
                                         TransportDeliveryFailureHandler failureHandler) {
        this.assignedDeliverySubmitter = Objects.requireNonNull(assignedDeliverySubmitter, "assignedDeliverySubmitter");
        this.routeKeyFactory = Objects.requireNonNull(routeKeyFactory, "routeKeyFactory");
        this.failureHandler = failureHandler;
    }

    @Override
    public void onTaskDispatchBatch(TaskDispatchContext task, List<TaskDispatchBinding> dispatchBindings) {
        if (task == null || dispatchBindings == null || dispatchBindings.isEmpty()) {
            return;
        }
        List<DeliveryCommand> commands = new ArrayList<>();
        List<TaskDispatchBinding> invalidBindings = new ArrayList<>();
        for (TaskDispatchBinding binding : dispatchBindings) {
            if (binding == null) {
                continue;
            }
            String adapterId = normalize(binding.adapterId());
            String selectedWorkerId = normalize(binding.workerId());
            if (adapterId == null || selectedWorkerId == null) {
                invalidBindings.add(binding);
                continue;
            }
            String routeKey = resolveRouteKey(task, binding);
            commands.add(toCommand(task, binding, adapterId, selectedWorkerId, routeKey));
        }

        compensateInvalidBindings(task, invalidBindings, "delivery command translation failed before transport handoff");
        assignedDeliverySubmitter.submit(commands);
    }

    private DeliveryCommand toCommand(TaskDispatchContext task,
                                      TaskDispatchBinding binding,
                                      String adapterId,
                                      String selectedWorkerId,
                                      String routeKey) {
        TaskDispatchItem dispatchItem = TaskDispatchItem.from(task, binding);
        String commandId = UUID.randomUUID().toString();
        String deliveryQueueKey = adapterId;
        return new DeliveryCommand(
                commandId,
                adapterId,
                selectedWorkerId,
                deliveryQueueKey,
                null,
                routeKey,
                null,
                packetFactory.fromDispatchView(commandId, adapterId, routeKey, null, dispatchItem),
                correlation(task, binding),
                0L,
                System.currentTimeMillis()
        );
    }

    private Map<String, String> correlation(TaskDispatchContext task, TaskDispatchBinding binding) {
        Map<String, String> values = new LinkedHashMap<>();
        put(values, "taskId", task.taskId());
        put(values, "messageId", binding.messageId());
        put(values, "attemptId", binding.attemptId());
        put(values, "attemptNo", Integer.toString(binding.attemptNo()));
        return values;
    }

    private String resolveRouteKey(TaskDispatchContext task, TaskDispatchBinding binding) {
        try {
            return normalize(routeKeyFactory.apply(
                    task,
                    binding
            ));
        } catch (RuntimeException e) {
            logger.warn("Failed to resolve routeKey for delivery command: taskId={}, messageId={}, reason={}",
                    task != null ? task.taskId() : null,
                    binding != null ? binding.messageId() : null,
                    e.getMessage());
            return null;
        }
    }

    private void handleDeliveryFailure(DeliveryCommand command, DispatchOutcome outcome, String detail) {
        if (command == null || outcome == null || !outcome.isRetryable()) {
            return;
        }
        if (failureHandler == null) {
            logger.warn("Cannot compensate delivery failure because no failure handler is configured: deliveryId={}, selectedWorkerId={}, detail={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), detail);
            return;
        }
        boolean handled = failureHandler.handle(command, outcome, detail);
        if (!handled) {
            logger.error("Delivery failure was not compensated: deliveryId={}, selectedWorkerId={}, detail={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), detail);
        }
    }

    private void compensateInvalidBindings(TaskDispatchContext task, List<TaskDispatchBinding> bindings, String detail) {
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        if (failureHandler == null) {
            logger.warn("Cannot compensate invalid delivery bindings because no failure handler is configured: taskId={}, bindings={}, detail={}",
                    task != null ? task.taskId() : null, bindings.size(), detail);
            return;
        }
        for (TaskDispatchBinding binding : bindings) {
            String adapterId = normalize(binding.adapterId());
            String selectedWorkerId = normalize(binding.workerId());
            if (adapterId == null) {
                adapterId = "unknown";
            }
            if (selectedWorkerId == null) {
                selectedWorkerId = "unknown";
            }
            DeliveryCommand command = toCommand(task, binding, adapterId, selectedWorkerId, null);
            handleDeliveryFailure(command, DispatchOutcome.unavailable(command, detail), detail);
        }
    }

    private static void put(Map<String, String> values, String key, String value) {
        String normalized = normalize(value);
        if (normalized != null) {
            values.put(key, normalized);
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
