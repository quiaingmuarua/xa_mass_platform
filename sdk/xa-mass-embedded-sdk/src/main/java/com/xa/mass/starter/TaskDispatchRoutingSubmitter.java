package com.xa.mass.starter;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.starter.AssignedDeliveryBatch;
import com.xa.mass.transport.starter.AssignedDeliveryMessage;
import com.xa.mass.transport.starter.AssignedDeliverySink;
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

    private final AssignedDeliverySink assignedDeliverySink;
    private final Function<String, Optional<SelectedWorkerDeliveryTargetEvidence>> deliveryTargetResolver;
    private final TaskDispatchPayloadEncoder payloadEncoder = new TaskDispatchPayloadEncoder();
    private final TaskDispatchDeliveryCorrelationCodec correlationCodec = new TaskDispatchDeliveryCorrelationCodec();

    TaskDispatchRoutingSubmitter(AssignedDeliverySink assignedDeliverySink,
                                 Function<String, Optional<SelectedWorkerDeliveryTargetEvidence>> deliveryTargetResolver) {
        this.assignedDeliverySink = Objects.requireNonNull(assignedDeliverySink, "assignedDeliverySink");
        this.deliveryTargetResolver = deliveryTargetResolver != null
                ? deliveryTargetResolver
                : selectedWorkerId -> Optional.empty();
    }

    @Override
    public void onTaskDispatchBatch(TaskDispatchContext task, List<TaskDispatchBinding> dispatchBindings) {
        if (task == null || dispatchBindings == null || dispatchBindings.isEmpty()) {
            return;
        }
        Map<String, List<AssignedDeliveryMessage>> itemsByMailbox = new LinkedHashMap<>();
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
            AssignedDeliveryMessage item = toItem(task, binding, selectedWorkerId);
            SelectedWorkerDeliveryTargetEvidence target = deliveryTargetResolver
                    .apply(selectedWorkerId)
                    .orElse(null);
            if (target == null || !target.isDeliverable(System.currentTimeMillis())) {
                logDeliveryTargetFailure(item, "selected worker has no current adapter mailbox target");
                continue;
            }
            if (!selectedWorkerId.equals(target.workerId())) {
                logDeliveryTargetFailure(item, "selected worker delivery target does not match assignment worker");
                continue;
            }
            itemsByMailbox.computeIfAbsent(target.adapterMailboxKey(), ignored -> new ArrayList<>()).add(item);
        }

        logInvalidBindings(task, invalidBindings, "delivery command translation failed before transport handoff");
        if (itemsByMailbox.isEmpty()) {
            return;
        }
        List<AssignedDeliveryBatch> batches = itemsByMailbox.entrySet().stream()
                .map(entry -> new AssignedDeliveryBatch(
                        entry.getKey(),
                        entry.getValue()))
                .toList();
        logRetryableOutcomes(assignedDeliverySink.submit(batches));
    }

    private AssignedDeliveryMessage toItem(TaskDispatchContext task,
                                           TaskDispatchBinding binding,
                                           String selectedWorkerId) {
        String correlationRef = correlationCodec.encode(task, binding);
        String deliveryId = UUID.randomUUID().toString();
        return new AssignedDeliveryMessage(
                deliveryId,
                selectedWorkerId,
                payloadEncoder.encode(task, binding, deliveryId, correlationRef),
                correlationRef,
                0L,
                System.currentTimeMillis()
        );
    }

    private void logInvalidBindings(TaskDispatchContext task, List<TaskDispatchBinding> bindings, String detail) {
        if (bindings == null || bindings.isEmpty()) {
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
            logRetryableOutcome(outcome);
        }
    }

    private void logDeliveryTargetFailure(AssignedDeliveryMessage item, String detail) {
        if (item == null) {
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
        logRetryableOutcome(outcome);
    }

    private void logRetryableOutcomes(List<DispatchOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return;
        }
        for (DispatchOutcome outcome : outcomes) {
            logRetryableOutcome(outcome);
        }
    }

    private void logRetryableOutcome(DispatchOutcome outcome) {
        if (outcome == null || !outcome.isRetryable()) {
            return;
        }
        logger.warn("Assigned delivery failed before worker result; engine attempt timeout remains recovery path: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                outcome.getDeliveryId(),
                outcome.getSelectedWorkerId(),
                outcome.getStatus(),
                outcome.getReason());
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
