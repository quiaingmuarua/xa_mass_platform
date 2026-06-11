package com.xa.mass.transport.runtime.worker;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.runtime.TransportDispatchFailureHandler;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.runtime.delivery.TransportDispatchEnvelopeFactory;
import com.xa.mass.transport.runtime.dispatch.RouteTargetedTaskDispatchBatch;
import com.xa.mass.transport.runtime.dispatch.RouteTargetedTaskDispatchBatchListener;
import com.xa.mass.transport.runtime.dispatch.RouteTargetedTaskDispatchBinding;
import com.xa.mass.transport.worker.WorkerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * Delivers route-targeted dispatch batches to local transport adapters.
 */
public final class RouteTargetedTaskDispatchListener implements RouteTargetedTaskDispatchBatchListener {

    private static final Logger logger = LoggerFactory.getLogger(RouteTargetedTaskDispatchListener.class);

    private final TransportRuntimeRegistry transportRuntimeRegistry;
    private final TransportDispatchFailureHandler failureHandler;
    private final TransportDispatchEnvelopeFactory envelopeFactory;
    private final RuntimeTaskExecutor runtimeTaskExecutor;

    public RouteTargetedTaskDispatchListener(TransportRuntimeRegistry transportRuntimeRegistry,
                                             TransportDispatchFailureHandler failureHandler,
                                             RuntimeTaskExecutor runtimeTaskExecutor) {
        this(
                transportRuntimeRegistry,
                failureHandler,
                new TransportDispatchEnvelopeFactory(),
                runtimeTaskExecutor
        );
    }

    RouteTargetedTaskDispatchListener(TransportRuntimeRegistry transportRuntimeRegistry,
                                      TransportDispatchFailureHandler failureHandler,
                                      TransportDispatchEnvelopeFactory envelopeFactory,
                                      RuntimeTaskExecutor runtimeTaskExecutor) {
        this.transportRuntimeRegistry = Objects.requireNonNull(transportRuntimeRegistry, "transportRuntimeRegistry");
        this.failureHandler = failureHandler;
        this.envelopeFactory = Objects.requireNonNull(envelopeFactory, "envelopeFactory");
        this.runtimeTaskExecutor = runtimeTaskExecutor;
    }

    @Override
    public void onRouteTargetedTaskDispatchBatch(RouteTargetedTaskDispatchBatch batch) {
        if (batch == null || batch.deliveryBindings().isEmpty()) {
            return;
        }
        Map<WorkerAdapter, List<TransportDispatchEnvelope>> groupedByAdapter = new LinkedHashMap<>();
        Map<String, TaskDispatchBinding> bindingByAttemptId = new LinkedHashMap<>();
        List<TaskDispatchBinding> unresolved = new ArrayList<>();
        for (RouteTargetedTaskDispatchBinding delivery : batch.deliveryBindings()) {
            TaskDispatchBinding binding = delivery.dispatchBinding();
            WorkerAdapter adapter = resolveAdapter(delivery.adapterId());
            if (adapter == null) {
                unresolved.add(binding);
                continue;
            }
            TaskDispatchItem payload = TaskDispatchItem.from(batch.task(), binding);
            String attemptId = payload.attemptId();
            if (attemptId != null && !attemptId.isBlank()) {
                bindingByAttemptId.put(attemptId, binding);
            }
            groupedByAdapter.computeIfAbsent(adapter, ignored -> new ArrayList<>())
                    .add(envelopeFactory.create(
                            delivery.adapterId(),
                            delivery.routeKey(),
                            null,
                            payload
                    ));
        }

        compensate(batch.task(), unresolved, "route-targeted dispatch adapter is unavailable");

        List<AdapterDispatchGroup> groups = new ArrayList<>(groupedByAdapter.size());
        for (Map.Entry<WorkerAdapter, List<TransportDispatchEnvelope>> entry : groupedByAdapter.entrySet()) {
            groups.add(new AdapterDispatchGroup(entry.getKey(), Collections.unmodifiableList(entry.getValue())));
        }

        for (DispatchGroupResult dispatchResult : dispatchGroups(groups)) {
            logDispatchOutcomes(dispatchResult.adapter(), dispatchResult.outcomes());
            compensateRetryableFailures(batch.task(), dispatchResult.outcomes(), bindingByAttemptId);
        }
    }

    private WorkerAdapter resolveAdapter(String adapterId) {
        try {
            return transportRuntimeRegistry.resolveDispatchAdapterByAdapterId(adapterId);
        } catch (RuntimeException e) {
            logger.warn("Cannot resolve route-targeted dispatch adapter: adapterId={}, reason={}",
                    adapterId, e.getMessage());
            return null;
        }
    }

    private List<DispatchGroupResult> dispatchGroups(List<AdapterDispatchGroup> groups) {
        if (groups.isEmpty()) {
            return List.of();
        }
        if (groups.size() == 1 || runtimeTaskExecutor == null) {
            List<DispatchGroupResult> results = new ArrayList<>(groups.size());
            for (AdapterDispatchGroup group : groups) {
                results.add(dispatchGroup(group));
            }
            return results;
        }

        List<Future<DispatchGroupResult>> futures = new ArrayList<>(groups.size());
        int submitted = 0;
        for (AdapterDispatchGroup group : groups) {
            try {
                futures.add(runtimeTaskExecutor.submit(() -> dispatchGroup(group)));
                submitted++;
            } catch (RejectedExecutionException e) {
                logger.warn("Route-targeted transport dispatch executor rejected adapter batch: adapterId={}, envelopes={}, reason={}",
                        adapterId(group.adapter()), group.envelopes().size(), e.getMessage());
                futures.add(null);
                break;
            }
        }

        List<DispatchGroupResult> results = new ArrayList<>(groups.size());
        for (int index = 0; index < groups.size(); index++) {
            AdapterDispatchGroup group = groups.get(index);
            Future<DispatchGroupResult> future = index < futures.size() ? futures.get(index) : null;
            if (future == null) {
                if (index < submitted) {
                    continue;
                }
                results.add(dispatchRejectedGroup(group, "route-targeted dispatch executor rejected adapter batch"));
                continue;
            }
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results.add(dispatchRejectedGroup(group, "route-targeted dispatch interrupted while awaiting adapter batch"));
            } catch (ExecutionException e) {
                results.add(dispatchFailedGroup(group, "route-targeted adapter batch failed", e.getCause()));
            }
        }
        return results;
    }

    private DispatchGroupResult dispatchGroup(AdapterDispatchGroup group) {
        try {
            List<DispatchOutcome> outcomes = group.adapter().dispatchEnvelopes(group.envelopes());
            return new DispatchGroupResult(group.adapter(), outcomes == null ? List.of() : outcomes);
        } catch (RuntimeException e) {
            return dispatchFailedGroup(group, "route-targeted adapter batch failed", e);
        }
    }

    private DispatchGroupResult dispatchFailedGroup(AdapterDispatchGroup group, String message, Throwable error) {
        logger.error("{}: adapterId={}, envelopes={}", message, adapterId(group.adapter()), group.envelopes().size(), error);
        return new DispatchGroupResult(group.adapter(), adapterUnavailableOutcomes(group, error != null ? error.getMessage() : message));
    }

    private DispatchGroupResult dispatchRejectedGroup(AdapterDispatchGroup group, String reason) {
        logger.warn("{}: adapterId={}, envelopes={}", reason, adapterId(group.adapter()), group.envelopes().size());
        return new DispatchGroupResult(group.adapter(), adapterUnavailableOutcomes(group, reason));
    }

    private List<DispatchOutcome> adapterUnavailableOutcomes(AdapterDispatchGroup group, String reason) {
        List<DispatchOutcome> outcomes = new ArrayList<>(group.envelopes().size());
        for (TransportDispatchEnvelope envelope : group.envelopes()) {
            outcomes.add(DispatchOutcome.adapterUnavailable(adapterId(group.adapter()), envelope, reason));
        }
        return Collections.unmodifiableList(outcomes);
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
                logger.debug("Route-targeted transport dispatch outcome: adapterId={}, routeKey={}, deliveryId={}, attemptId={}, status={}",
                        outcome.getAdapterId(), outcome.getRouteKey(), outcome.getDeliveryId(),
                        outcome.getAttemptId(), outcome.getStatus());
                continue;
            }
            logger.warn("Route-targeted transport dispatch outcome: adapterId={}, routeKey={}, deliveryId={}, attemptId={}, status={}, retryable={}, reason={}, routedAdapter={}",
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
                continue;
            }
            TaskDispatchBinding binding = bindingByAttemptId.get(attemptId);
            if (binding == null) {
                continue;
            }
            retryableBindings.add(binding);
            statuses.add(outcome.getStatus().name());
        }
        if (retryableBindings.isEmpty()) {
            return;
        }
        compensate(task, retryableBindings, "route-targeted transport dispatch failed after assignment: statuses=" + statuses);
    }

    private void compensate(TaskDispatchContext task, List<TaskDispatchBinding> dispatchBindings, String detail) {
        if (dispatchBindings == null || dispatchBindings.isEmpty()) {
            return;
        }
        if (failureHandler == null) {
            logger.warn("Route-targeted dispatch failure has no compensation handler: taskId={}, bindings={}, detail={}",
                    task != null ? task.taskId() : null, dispatchBindings.size(), detail);
            return;
        }
        boolean compensated = failureHandler.compensate(task, List.copyOf(dispatchBindings), detail);
        if (!compensated) {
            logger.error("Route-targeted dispatch failure was not compensated: taskId={}, bindings={}, detail={}",
                    task != null ? task.taskId() : null, dispatchBindings.size(), detail);
        }
    }

    private static String adapterId(WorkerAdapter adapter) {
        return adapter != null ? adapter.adapterId() : null;
    }

    private record AdapterDispatchGroup(WorkerAdapter adapter, List<TransportDispatchEnvelope> envelopes) {
    }

    private record DispatchGroupResult(WorkerAdapter adapter, List<DispatchOutcome> outcomes) {
    }
}
