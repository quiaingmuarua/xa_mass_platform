package com.xa.mass.starter;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TaskDispatchContent;
import com.xa.mass.transport.model.TaskDispatchExecutionContext;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandGroup;
import com.xa.mass.transport.runtime.delivery.TransportAssignedDeliverySubmitter;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureEvent;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Starter-owned translator from engine assignment bindings to transport
 * delivery commands.
 */
final class TaskDispatchDeliveryCommandSubmitter implements TaskDispatchBatchListener {

    private static final Logger logger = LoggerFactory.getLogger(TaskDispatchDeliveryCommandSubmitter.class);

    private final TransportAssignedDeliverySubmitter assignedDeliverySubmitter;
    private final TransportDeliveryFailureHandler failureHandler;

    TaskDispatchDeliveryCommandSubmitter(TransportAssignedDeliverySubmitter assignedDeliverySubmitter,
                                         TransportDeliveryFailureHandler failureHandler) {
        this.assignedDeliverySubmitter = Objects.requireNonNull(assignedDeliverySubmitter, "assignedDeliverySubmitter");
        this.failureHandler = failureHandler;
    }

    @Override
    public void onTaskDispatchBatch(TaskDispatchContext task, List<TaskDispatchBinding> dispatchBindings) {
        if (task == null || dispatchBindings == null || dispatchBindings.isEmpty()) {
            return;
        }
        Map<String, List<DeliveryCommand>> commandsByAdapter = new LinkedHashMap<>();
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
            commandsByAdapter.computeIfAbsent(adapterId, ignored -> new ArrayList<>())
                    .add(toCommand(task, binding, selectedWorkerId));
        }

        compensateInvalidBindings(task, invalidBindings, "delivery command translation failed before transport handoff");
        if (commandsByAdapter.isEmpty()) {
            return;
        }
        List<DeliveryCommandGroup> groups = commandsByAdapter.entrySet().stream()
                .map(entry -> new DeliveryCommandGroup(entry.getKey(), entry.getValue()))
                .toList();
        assignedDeliverySubmitter.submit(groups);
    }

    private DeliveryCommand toCommand(TaskDispatchContext task,
                                      TaskDispatchBinding binding,
                                      String selectedWorkerId) {
        return new DeliveryCommand(
                UUID.randomUUID().toString(),
                selectedWorkerId,
                TaskDispatchContent.from(task, binding),
                TaskDispatchExecutionContext.from(task, binding),
                0L,
                System.currentTimeMillis()
        );
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
            String deliveryId = UUID.randomUUID().toString();
            TaskDispatchContent content = TaskDispatchContent.from(task, binding);
            TaskDispatchExecutionContext executionContext = TaskDispatchExecutionContext.from(task, binding);
            DispatchOutcome outcome = new DispatchOutcome(
                    deliveryId,
                    adapterId,
                    selectedWorkerId,
                    adapterId,
                    null,
                    executionContext.attemptId(),
                    content.taskId(),
                    content.messageId(),
                    executionContext.attemptNo(),
                    DispatchOutcomeStatus.UNAVAILABLE,
                    true,
                    detail,
                    null,
                    null,
                    System.currentTimeMillis()
            );
            boolean handled = failureHandler.handle(new TransportDeliveryFailureEvent(outcome, detail));
            if (!handled) {
                logger.error("Delivery failure was not compensated: deliveryId={}, selectedWorkerId={}, detail={}",
                        outcome.getDeliveryId(), outcome.getSelectedWorkerId(), detail);
            }
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
