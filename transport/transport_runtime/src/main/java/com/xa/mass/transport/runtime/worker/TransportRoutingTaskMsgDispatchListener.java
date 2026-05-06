package com.xa.mass.transport.runtime.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskMsgDispatchListener;
import com.xa.mass.storage.api.WorkerLookupStore;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.runtime.delivery.TransportDispatchEnvelopeFactory;
import com.xa.mass.transport.worker.WorkerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Routes logical task dispatches to the transport adapter selected by each
 * worker's resolved adapter identity.
 */
public class TransportRoutingTaskMsgDispatchListener implements TaskMsgDispatchListener {

    private static final Logger logger = LoggerFactory.getLogger(TransportRoutingTaskMsgDispatchListener.class);

    private final WorkerLookupStore workerLookupStore;
    private final TransportRuntimeRegistry transportRuntimeRegistry;
    private final TransportDispatchEnvelopeFactory envelopeFactory;

    public TransportRoutingTaskMsgDispatchListener(WorkerLookupStore workerLookupStore,
                                                   TransportRuntimeRegistry transportRuntimeRegistry) {
        this(workerLookupStore, transportRuntimeRegistry, new TransportDispatchEnvelopeFactory());
    }

    TransportRoutingTaskMsgDispatchListener(WorkerLookupStore workerLookupStore,
                                            TransportRuntimeRegistry transportRuntimeRegistry,
                                            TransportDispatchEnvelopeFactory envelopeFactory) {
        this.workerLookupStore = Objects.requireNonNull(workerLookupStore, "workerLookupStore");
        this.transportRuntimeRegistry = Objects.requireNonNull(transportRuntimeRegistry, "transportRuntimeRegistry");
        this.envelopeFactory = Objects.requireNonNull(envelopeFactory, "envelopeFactory");
    }

    @Override
    public void onTaskMsgsReady(Task task, List<TaskDispatchBinding> dispatchBindings) {
        if (task == null || dispatchBindings == null || dispatchBindings.isEmpty()) {
            return;
        }

        Map<WorkerAdapter, List<TransportDispatchEnvelope>> grouped = new LinkedHashMap<>();
        for (TaskDispatchBinding binding : dispatchBindings) {
            TransportBinding transportBinding = resolveBinding(binding);
            WorkerAdapter adapter = transportBinding.getWorkerAdapter();
            TaskDispatchItem payload = TaskDispatchItem.from(task, binding.taskMsg(), binding.attempt());
            grouped.computeIfAbsent(adapter, ignored -> new ArrayList<>())
                    .add(envelopeFactory.create(
                            adapter.adapterId(),
                            transportBinding.resolveRouteKey(binding, payload),
                            payload.runtimeMetadata().attemptId(),
                            payload
                    ));
        }

        for (Map.Entry<WorkerAdapter, List<TransportDispatchEnvelope>> entry : grouped.entrySet()) {
            List<DispatchOutcome> outcomes = entry.getKey().dispatchEnvelopes(List.copyOf(entry.getValue()));
            logDispatchOutcomes(entry.getKey(), outcomes);
        }
    }

    private void logDispatchOutcomes(WorkerAdapter adapter, List<DispatchOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return;
        }
        for (DispatchOutcome outcome : outcomes) {
            if (outcome == null) {
                continue;
            }
            if (outcome.getStatus() == DispatchOutcomeStatus.SENT
                    || outcome.getStatus() == DispatchOutcomeStatus.QUEUED) {
                logger.debug("Transport dispatch outcome: adapterId={}, routeKey={}, deliveryId={}, correlationKey={}, status={}",
                        outcome.getAdapterId(), outcome.getRouteKey(), outcome.getDeliveryId(),
                        outcome.getCorrelationKey(), outcome.getStatus());
                continue;
            }
            logger.warn("Transport dispatch outcome: adapterId={}, routeKey={}, deliveryId={}, correlationKey={}, status={}, retryable={}, reason={}, routedAdapter={}",
                    outcome.getAdapterId(), outcome.getRouteKey(), outcome.getDeliveryId(),
                    outcome.getCorrelationKey(), outcome.getStatus(), outcome.isRetryable(), outcome.getReason(),
                    adapter != null ? adapter.adapterId() : null);
        }
    }

    private TransportBinding resolveBinding(TaskDispatchBinding binding) {
        String workerId = binding != null && binding.attempt() != null ? binding.attempt().getWorkerId() : null;
        if (workerId == null || workerLookupStore.findWorker(workerId) == null) {
            throw new IllegalStateException("Cannot dispatch task item because worker is missing: " + workerId);
        }
        return transportRuntimeRegistry.resolveDispatchBinding(workerId);
    }
}
