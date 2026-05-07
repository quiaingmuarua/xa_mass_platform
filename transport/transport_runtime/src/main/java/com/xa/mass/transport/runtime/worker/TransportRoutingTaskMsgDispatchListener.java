package com.xa.mass.transport.runtime.worker;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.storage.api.WorkerLookupStore;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportDispatchFailureHandler;
import com.xa.mass.transport.runtime.TransportDispatchRouteContext;
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
public class TransportRoutingTaskMsgDispatchListener implements TaskDispatchBatchListener {

    private static final Logger logger = LoggerFactory.getLogger(TransportRoutingTaskMsgDispatchListener.class);

    private final WorkerLookupStore workerLookupStore;
    private final TransportRuntimeRegistry transportRuntimeRegistry;
    private final TransportDispatchFailureHandler failureHandler;
    private final TransportDispatchEnvelopeFactory envelopeFactory;

    public TransportRoutingTaskMsgDispatchListener(WorkerLookupStore workerLookupStore,
                                                   TransportRuntimeRegistry transportRuntimeRegistry) {
        this(workerLookupStore, transportRuntimeRegistry, null, new TransportDispatchEnvelopeFactory());
    }

    public TransportRoutingTaskMsgDispatchListener(WorkerLookupStore workerLookupStore,
                                                   TransportRuntimeRegistry transportRuntimeRegistry,
                                                   TransportDispatchFailureHandler failureHandler) {
        this(workerLookupStore, transportRuntimeRegistry, failureHandler, new TransportDispatchEnvelopeFactory());
    }

    TransportRoutingTaskMsgDispatchListener(WorkerLookupStore workerLookupStore,
                                            TransportRuntimeRegistry transportRuntimeRegistry,
                                            TransportDispatchFailureHandler failureHandler,
                                            TransportDispatchEnvelopeFactory envelopeFactory) {
        this.workerLookupStore = Objects.requireNonNull(workerLookupStore, "workerLookupStore");
        this.transportRuntimeRegistry = Objects.requireNonNull(transportRuntimeRegistry, "transportRuntimeRegistry");
        this.failureHandler = failureHandler;
        this.envelopeFactory = Objects.requireNonNull(envelopeFactory, "envelopeFactory");
    }

    @Override
    public void onTaskDispatchBatch(TaskDispatchContext task, List<TaskDispatchBinding> dispatchBindings) {
        if (task == null || dispatchBindings == null || dispatchBindings.isEmpty()) {
            return;
        }

        Map<WorkerAdapter, List<TransportDispatchEnvelope>> grouped = new LinkedHashMap<>();
        Map<String, TaskDispatchBinding> bindingByAttemptId = new LinkedHashMap<>();
        Map<String, ResolvedDispatchTarget> dispatchTargetByWorkerId = new HashMap<>();
        for (TaskDispatchBinding binding : dispatchBindings) {
            ResolvedDispatchTarget target = resolveDispatchTarget(binding, dispatchTargetByWorkerId);
            TransportBinding transportBinding = target.binding();
            WorkerAdapter adapter = target.adapter();
            TransportDispatchRouteContext routeContext = TransportDispatchRouteContext.from(task, binding);
            TaskDispatchItem payload = TaskDispatchItem.from(task, binding);
            String attemptId = payload.attemptId();
            if (attemptId != null && !attemptId.isBlank()) {
                bindingByAttemptId.put(attemptId, binding);
            }
             grouped.computeIfAbsent(adapter, ignored -> new ArrayList<>())
                    .add(envelopeFactory.create(
                            adapter.adapterId(),
                            transportBinding.resolveRouteKey(binding, routeContext),
                            null,
                            payload
                    ));
        }

        for (Map.Entry<WorkerAdapter, List<TransportDispatchEnvelope>> entry : grouped.entrySet()) {
            List<DispatchOutcome> outcomes = entry.getKey().dispatchEnvelopes(Collections.unmodifiableList(entry.getValue()));
            logDispatchOutcomes(entry.getKey(), outcomes);
            compensateRetryableFailures(task, outcomes, bindingByAttemptId);
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
                logger.debug("Transport dispatch outcome: adapterId={}, routeKey={}, deliveryId={}, attemptId={}, status={}",
                        outcome.getAdapterId(), outcome.getRouteKey(), outcome.getDeliveryId(),
                        outcome.getAttemptId(), outcome.getStatus());
                continue;
            }
            logger.warn("Transport dispatch outcome: adapterId={}, routeKey={}, deliveryId={}, attemptId={}, status={}, retryable={}, reason={}, routedAdapter={}",
                    outcome.getAdapterId(), outcome.getRouteKey(), outcome.getDeliveryId(),
                    outcome.getAttemptId(), outcome.getStatus(), outcome.isRetryable(), outcome.getReason(),
                    adapter != null ? adapter.adapterId() : null);
        }
    }

    private void compensateRetryableFailures(TaskDispatchContext task,
                                             List<DispatchOutcome> outcomes,
                                             Map<String, TaskDispatchBinding> bindingByAttemptId) {
        if (task == null || outcomes == null || outcomes.isEmpty() || bindingByAttemptId.isEmpty()) {
            return;
        }
        List<TaskDispatchBinding> retryableBindings = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        for (DispatchOutcome outcome : outcomes) {
            if (outcome == null || !outcome.isRetryable()) {
                continue;
            }
            String attemptId = outcome.getAttemptId();
            if (attemptId == null || attemptId.isBlank()) {
                logger.warn("Skip retryable dispatch compensation because outcome attemptId is missing: adapterId={}, routeKey={}, deliveryId={}, status={}, reason={}",
                        outcome.getAdapterId(), outcome.getRouteKey(), outcome.getDeliveryId(),
                        outcome.getStatus(), outcome.getReason());
                continue;
            }
            TaskDispatchBinding binding = bindingByAttemptId.get(attemptId);
            if (binding == null) {
                logger.warn("Skip retryable dispatch compensation because no dispatch binding matched attemptId={}: adapterId={}, routeKey={}, deliveryId={}, status={}, reason={}",
                        attemptId, outcome.getAdapterId(), outcome.getRouteKey(), outcome.getDeliveryId(),
                        outcome.getStatus(), outcome.getReason());
                continue;
            }
            retryableBindings.add(binding);
            statuses.add(outcome.getStatus().name());
        }
        if (retryableBindings.isEmpty()) {
            return;
        }
        if (failureHandler == null) {
            logger.warn("Retryable transport dispatch failure has no compensation handler: taskId={}, bindings={}, statuses={}",
                    task.taskId(), retryableBindings.size(), statuses);
            return;
        }
        String detail = "transport dispatch failed after assignment: statuses=" + statuses;
        boolean compensated = failureHandler.compensate(task, List.copyOf(retryableBindings), detail);
        if (!compensated) {
            logger.error("Retryable transport dispatch failure was not compensated: taskId={}, bindings={}, statuses={}",
                    task.taskId(), retryableBindings.size(), statuses);
        }
    }

    private ResolvedDispatchTarget resolveDispatchTarget(TaskDispatchBinding binding,
                                                         Map<String, ResolvedDispatchTarget> dispatchTargetByWorkerId) {
        String workerId = binding != null ? binding.workerId() : null;
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalStateException("Cannot dispatch task item because worker is missing: " + workerId);
        }
        return dispatchTargetByWorkerId.computeIfAbsent(workerId, this::resolveDispatchTarget);
    }

    private ResolvedDispatchTarget resolveDispatchTarget(String workerId) {
        Worker worker = workerLookupStore.findWorker(workerId);
        if (worker == null) {
            throw new IllegalStateException("Cannot dispatch task item because worker is missing: " + workerId);
        }
        TransportBinding binding = transportRuntimeRegistry.resolveDispatchBinding(worker);
        return new ResolvedDispatchTarget(binding, binding.getWorkerAdapter());
    }

    private record ResolvedDispatchTarget(TransportBinding binding, WorkerAdapter adapter) {
    }
}
