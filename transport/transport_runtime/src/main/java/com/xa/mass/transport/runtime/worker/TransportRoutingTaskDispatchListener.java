package com.xa.mass.transport.runtime.worker;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.runtime.worker.WorkerResourceRecord;
import com.xa.mass.runtime.worker.WorkerResourceRuntime;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * Routes logical task dispatches to the transport adapter selected by each
 * worker's resolved adapter identity.
 */
public class TransportRoutingTaskDispatchListener implements TaskDispatchBatchListener {

    private static final Logger logger = LoggerFactory.getLogger(TransportRoutingTaskDispatchListener.class);

    private final WorkerResourceRuntime workerResourceRuntime;
    private final TransportRuntimeRegistry transportRuntimeRegistry;
    private final TransportDispatchFailureHandler failureHandler;
    private final TransportDispatchEnvelopeFactory envelopeFactory;
    private final RuntimeTaskExecutor runtimeTaskExecutor;

    public TransportRoutingTaskDispatchListener(WorkerResourceRuntime workerResourceRuntime,
                                                TransportRuntimeRegistry transportRuntimeRegistry) {
        this(workerResourceRuntime, transportRuntimeRegistry, null, new TransportDispatchEnvelopeFactory(), null);
    }

    public TransportRoutingTaskDispatchListener(WorkerResourceRuntime workerResourceRuntime,
                                                TransportRuntimeRegistry transportRuntimeRegistry,
                                                TransportDispatchFailureHandler failureHandler) {
        this(workerResourceRuntime, transportRuntimeRegistry, failureHandler, new TransportDispatchEnvelopeFactory(), null);
    }

    TransportRoutingTaskDispatchListener(WorkerResourceRuntime workerResourceRuntime,
                                         TransportRuntimeRegistry transportRuntimeRegistry,
                                         TransportDispatchFailureHandler failureHandler,
                                         TransportDispatchEnvelopeFactory envelopeFactory) {
        this(workerResourceRuntime, transportRuntimeRegistry, failureHandler, envelopeFactory, null);
    }

    public TransportRoutingTaskDispatchListener(WorkerResourceRuntime workerResourceRuntime,
                                                TransportRuntimeRegistry transportRuntimeRegistry,
                                                TransportDispatchFailureHandler failureHandler,
                                                RuntimeTaskExecutor runtimeTaskExecutor) {
        this(workerResourceRuntime, transportRuntimeRegistry, failureHandler, new TransportDispatchEnvelopeFactory(), runtimeTaskExecutor);
    }

    TransportRoutingTaskDispatchListener(WorkerResourceRuntime workerResourceRuntime,
                                         TransportRuntimeRegistry transportRuntimeRegistry,
                                         TransportDispatchFailureHandler failureHandler,
                                         TransportDispatchEnvelopeFactory envelopeFactory,
                                         RuntimeTaskExecutor runtimeTaskExecutor) {
        this.workerResourceRuntime = Objects.requireNonNull(workerResourceRuntime, "workerResourceRuntime");
        this.transportRuntimeRegistry = Objects.requireNonNull(transportRuntimeRegistry, "transportRuntimeRegistry");
        this.failureHandler = failureHandler;
        this.envelopeFactory = Objects.requireNonNull(envelopeFactory, "envelopeFactory");
        this.runtimeTaskExecutor = runtimeTaskExecutor;
    }

    @Override
    public void onTaskDispatchBatch(TaskDispatchContext task, List<TaskDispatchBinding> dispatchBindings) {
        if (task == null || dispatchBindings == null || dispatchBindings.isEmpty()) {
            return;
        }

        Map<WorkerAdapter, List<TransportDispatchEnvelope>> groupedByAdapter = new LinkedHashMap<>();
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
            groupedByAdapter.computeIfAbsent(adapter, ignored -> new ArrayList<>())
                    .add(envelopeFactory.create(
                            adapter.adapterId(),
                            transportBinding.resolveRouteKey(binding, routeContext),
                            null,
                            payload
                    ));
        }

        List<AdapterDispatchGroup> groups = new ArrayList<>(groupedByAdapter.size());
        for (Map.Entry<WorkerAdapter, List<TransportDispatchEnvelope>> entry : groupedByAdapter.entrySet()) {
            groups.add(new AdapterDispatchGroup(entry.getKey(), Collections.unmodifiableList(entry.getValue())));
        }

        for (DispatchGroupResult dispatchResult : dispatchGroups(groups)) {
            logDispatchOutcomes(dispatchResult.adapter(), dispatchResult.outcomes());
            compensateRetryableFailures(task, dispatchResult.outcomes(), bindingByAttemptId);
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
                logger.warn("Transport dispatch fan-out executor rejected adapter batch: adapterId={}, envelopes={}, reason={}",
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
                results.add(dispatchRejectedGroup(group, "transport dispatch fan-out executor rejected adapter batch"));
                continue;
            }
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results.add(dispatchRejectedGroup(group, "transport dispatch fan-out interrupted while awaiting adapter batch"));
            } catch (ExecutionException e) {
                results.add(dispatchFailedGroup(group, "transport adapter batch failed", e.getCause()));
            }
        }
        return results;
    }

    private DispatchGroupResult dispatchGroup(AdapterDispatchGroup group) {
        try {
            List<DispatchOutcome> outcomes = group.adapter().dispatchEnvelopes(group.envelopes());
            return new DispatchGroupResult(group.adapter(), outcomes == null ? List.of() : outcomes);
        } catch (RuntimeException e) {
            return dispatchFailedGroup(group, "transport adapter batch failed", e);
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
        WorkerResourceRecord worker = workerResourceRuntime.worker(workerId).orElse(null);
        if (worker == null) {
            throw new IllegalStateException("Cannot dispatch task item because worker is missing: " + workerId);
        }
        TransportBinding binding = transportRuntimeRegistry.resolveDispatchBinding(worker);
        return new ResolvedDispatchTarget(binding, binding.getWorkerAdapter());
    }

    private static String adapterId(WorkerAdapter adapter) {
        return adapter != null ? adapter.adapterId() : null;
    }

    private record AdapterDispatchGroup(WorkerAdapter adapter, List<TransportDispatchEnvelope> envelopes) {
    }

    private record DispatchGroupResult(WorkerAdapter adapter, List<DispatchOutcome> outcomes) {
    }

    private record ResolvedDispatchTarget(TransportBinding binding, WorkerAdapter adapter) {
    }
}
