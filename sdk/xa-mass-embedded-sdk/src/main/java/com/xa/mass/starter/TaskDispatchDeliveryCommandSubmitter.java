package com.xa.mass.starter;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxDeliveryCommand;
import com.xa.mass.transport.runtime.delivery.TransportAssignedDeliverySubmitter;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureEvent;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureHandler;
import com.xa.mass.worker.runtime.evidence.SelectedWorkerDeliveryTargetEvidence;
import com.xa.mass.worker.runtime.evidence.WorkerDeliveryTargetView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
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
    private final WorkerDeliveryTargetView deliveryTargetView;
    private final TaskDispatchPayloadEncoder payloadEncoder = new TaskDispatchPayloadEncoder();
    private final TaskDispatchDeliveryCorrelationCodec correlationCodec = new TaskDispatchDeliveryCorrelationCodec();

    TaskDispatchDeliveryCommandSubmitter(TransportAssignedDeliverySubmitter assignedDeliverySubmitter,
                                         TransportDeliveryFailureHandler failureHandler,
                                         WorkerDeliveryTargetView deliveryTargetView) {
        this.assignedDeliverySubmitter = Objects.requireNonNull(assignedDeliverySubmitter, "assignedDeliverySubmitter");
        this.failureHandler = failureHandler;
        this.deliveryTargetView = deliveryTargetView != null
                ? deliveryTargetView
                : WorkerDeliveryTargetView.unavailable();
    }

    @Override
    public void onTaskDispatchBatch(TaskDispatchContext task, List<TaskDispatchBinding> dispatchBindings) {
        if (task == null || dispatchBindings == null || dispatchBindings.isEmpty()) {
            return;
        }
        List<AdapterMailboxDeliveryCommand> commands = new ArrayList<>();
        List<TaskDispatchBinding> invalidBindings = new ArrayList<>();
        for (TaskDispatchBinding binding : dispatchBindings) {
            if (binding == null) {
                continue;
            }
            String deliveryBucketId = normalize(binding.workerGroupId());
            String selectedWorkerId = normalize(binding.workerId());
            if (deliveryBucketId == null || selectedWorkerId == null) {
                invalidBindings.add(binding);
                continue;
            }
            DeliveryCommand command = toCommand(task, binding, deliveryBucketId, selectedWorkerId);
            SelectedWorkerDeliveryTargetEvidence target = deliveryTargetView
                    .resolveDeliveryTarget(selectedWorkerId)
                    .orElse(null);
            if (target == null || !target.isDeliverable(System.currentTimeMillis())) {
                compensateDeliveryTargetFailure(command, "selected worker has no current adapter mailbox target");
                continue;
            }
            if (!selectedWorkerId.equals(target.workerId())) {
                compensateDeliveryTargetFailure(command, "selected worker delivery target does not match assignment worker");
                continue;
            }
            commands.add(new AdapterMailboxDeliveryCommand(target.adapterMailboxKey(), command));
        }

        compensateInvalidBindings(task, invalidBindings, "delivery command translation failed before transport handoff");
        if (commands.isEmpty()) {
            return;
        }
        assignedDeliverySubmitter.submit(commands);
    }

    private DeliveryCommand toCommand(TaskDispatchContext task,
                                      TaskDispatchBinding binding,
                                      String deliveryBucketId,
                                      String selectedWorkerId) {
        String correlationRef = correlationCodec.encode(task, binding);
        return new DeliveryCommand(
                UUID.randomUUID().toString(),
                deliveryBucketId,
                selectedWorkerId,
                payloadEncoder.encode(task, binding, correlationRef),
                correlationRef,
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
            String selectedWorkerId = normalize(binding.workerId());
            String deliveryId = UUID.randomUUID().toString();
            DispatchOutcome outcome = new DispatchOutcome(
                    deliveryId,
                    selectedWorkerId,
                    correlationCodec.encode(task, binding),
                    DispatchOutcomeStatus.UNAVAILABLE,
                    true,
                    detail,
                    System.currentTimeMillis()
            );
            boolean handled = failureHandler.handle(new TransportDeliveryFailureEvent(outcome, detail));
            if (!handled) {
                logger.error("Delivery failure was not compensated: deliveryId={}, selectedWorkerId={}, detail={}",
                        outcome.getDeliveryId(), outcome.getSelectedWorkerId(), detail);
            }
        }
    }

    private void compensateDeliveryTargetFailure(DeliveryCommand command, String detail) {
        if (command == null) {
            return;
        }
        if (failureHandler == null) {
            logger.warn("Cannot compensate delivery target failure because no failure handler is configured: deliveryId={}, selectedWorkerId={}, detail={}",
                    command.getCommandId(), command.getSelectedWorkerId(), detail);
            return;
        }
        DispatchOutcome outcome = DispatchOutcome.fromCommand(
                command,
                DispatchOutcomeStatus.NO_ENDPOINT,
                true,
                detail
        );
        boolean handled = failureHandler.handle(new TransportDeliveryFailureEvent(outcome, detail));
        if (!handled) {
            logger.error("Delivery target failure was not compensated: deliveryId={}, selectedWorkerId={}, detail={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), detail);
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
