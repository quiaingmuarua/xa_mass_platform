package com.xa.mass.starter;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxDispatchBatch;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.delivery.TransportAssignedDeliverySubmitter;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureEvent;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureHandler;
import com.xa.mass.worker.runtime.evidence.SelectedWorkerDeliveryTargetEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Starter-owned translator from engine assignment bindings to transport
 * dispatch routing batches.
 */
final class TaskDispatchRoutingSubmitter implements TaskDispatchBatchListener {

    private static final Logger logger = LoggerFactory.getLogger(TaskDispatchRoutingSubmitter.class);

    private final TransportAssignedDeliverySubmitter assignedDeliverySubmitter;
    private final TransportDeliveryFailureHandler failureHandler;
    private final Function<String, Optional<SelectedWorkerDeliveryTargetEvidence>> deliveryTargetResolver;
    private final TaskDispatchPayloadEncoder payloadEncoder = new TaskDispatchPayloadEncoder();
    private final TaskDispatchDeliveryCorrelationCodec correlationCodec = new TaskDispatchDeliveryCorrelationCodec();

    TaskDispatchRoutingSubmitter(TransportAssignedDeliverySubmitter assignedDeliverySubmitter,
                                  TransportDeliveryFailureHandler failureHandler,
                                  Function<String, Optional<SelectedWorkerDeliveryTargetEvidence>> deliveryTargetResolver) {
        this.assignedDeliverySubmitter = Objects.requireNonNull(assignedDeliverySubmitter, "assignedDeliverySubmitter");
        this.failureHandler = failureHandler;
        this.deliveryTargetResolver = deliveryTargetResolver != null
                ? deliveryTargetResolver
                : selectedWorkerId -> Optional.empty();
    }

    @Override
    public void onTaskDispatchBatch(TaskDispatchContext task, List<TaskDispatchBinding> dispatchBindings) {
        if (task == null || dispatchBindings == null || dispatchBindings.isEmpty()) {
            return;
        }
        Map<String, List<DispatchMessage>> itemsByMailbox = new LinkedHashMap<>();
        List<TaskDispatchBinding> invalidBindings = new ArrayList<>();
        for (TaskDispatchBinding binding : dispatchBindings) {
            if (binding == null) {
                continue;
            }
            String selectedWorkerId = normalize(binding.workerId());
            if (selectedWorkerId == null) {
                invalidBindings.add(binding);
                continue;
            }
            DispatchMessage item = toItem(task, binding, selectedWorkerId);
            SelectedWorkerDeliveryTargetEvidence target = deliveryTargetResolver
                    .apply(selectedWorkerId)
                    .orElse(null);
            if (target == null || !target.isDeliverable(System.currentTimeMillis())) {
                compensateDeliveryTargetFailure(item, "selected worker has no current adapter mailbox target");
                continue;
            }
            if (!selectedWorkerId.equals(target.workerId())) {
                compensateDeliveryTargetFailure(item, "selected worker delivery target does not match assignment worker");
                continue;
            }
            itemsByMailbox.computeIfAbsent(target.adapterMailboxKey(), ignored -> new ArrayList<>()).add(item);
        }

        compensateInvalidBindings(task, invalidBindings, "delivery command translation failed before transport handoff");
        if (itemsByMailbox.isEmpty()) {
            return;
        }
        List<AdapterMailboxDispatchBatch> batches = itemsByMailbox.entrySet().stream()
                .map(entry -> new AdapterMailboxDispatchBatch(
                        entry.getKey(),
                        entry.getValue()))
                .toList();
        assignedDeliverySubmitter.submit(batches);
    }

    private DispatchMessage toItem(TaskDispatchContext task,
                                       TaskDispatchBinding binding,
                                       String selectedWorkerId) {
        String correlationRef = correlationCodec.encode(task, binding);
        String deliveryId = UUID.randomUUID().toString();
        return new DispatchMessage(
                deliveryId,
                selectedWorkerId,
                payloadEncoder.encode(task, binding, deliveryId, correlationRef),
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

    private void compensateDeliveryTargetFailure(DispatchMessage item, String detail) {
        if (item == null) {
            return;
        }
        if (failureHandler == null) {
            logger.warn("Cannot compensate delivery target failure because no failure handler is configured: deliveryId={}, selectedWorkerId={}, detail={}",
                    item.deliveryId(), item.selectedWorkerId(), detail);
            return;
        }
        DispatchOutcome outcome = new DispatchOutcome(
                item.deliveryId(),
                item.selectedWorkerId(),
                item.correlationRef(),
                DispatchOutcomeStatus.NO_ENDPOINT,
                true,
                detail,
                System.currentTimeMillis()
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
