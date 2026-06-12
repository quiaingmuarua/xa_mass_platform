package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.model.AdapterDispatchRequest;
import com.xa.mass.transport.model.AdapterEndpoint;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.route.WorkerDispatchRouteOwner;
import com.xa.mass.transport.route.WorkerDispatchRouteOwnerView;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
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
 * Delivers command batches to local adapters.
 */
public final class TransportDeliveryCommandListener {

    private static final Logger logger = LoggerFactory.getLogger(TransportDeliveryCommandListener.class);

    private final TransportRuntimeRegistry transportRuntimeRegistry;
    private final WorkerDispatchRouteOwnerView routeOwnerView;
    private final String localTransportNodeId;
    private final TransportDeliveryFailureHandler failureHandler;
    private final RuntimeTaskExecutor runtimeTaskExecutor;

    public TransportDeliveryCommandListener(TransportRuntimeRegistry transportRuntimeRegistry,
                                            WorkerDispatchRouteOwnerView routeOwnerView,
                                            String localTransportNodeId,
                                            TransportDeliveryFailureHandler failureHandler,
                                            RuntimeTaskExecutor runtimeTaskExecutor) {
        this.transportRuntimeRegistry = Objects.requireNonNull(transportRuntimeRegistry, "transportRuntimeRegistry");
        this.routeOwnerView = Objects.requireNonNull(routeOwnerView, "routeOwnerView");
        this.localTransportNodeId = normalizeText(localTransportNodeId);
        this.failureHandler = failureHandler;
        this.runtimeTaskExecutor = runtimeTaskExecutor;
    }

    public List<DispatchOutcome> onDeliveryCommandBatch(DeliveryCommandBatch batch) {
        if (batch == null || batch.items().isEmpty()) {
            return List.of();
        }

        WorkerAdapter adapter = resolveAdapter(batch.adapterId());

        Map<WorkerAdapter, List<AdapterDispatchRequest>> groupedByAdapter = new LinkedHashMap<>();
        Map<String, ResolvedDispatchCommand> itemByDeliveryId = new LinkedHashMap<>();
        List<DispatchOutcome> immediateOutcomes = new ArrayList<>();
        for (DeliveryCommand command : batch.items()) {
            AdapterEndpoint endpoint = null;
            try {
                endpoint = resolveEndpoint(batch, command);
                if (adapter == null) {
                    DispatchOutcome outcome = DispatchOutcome.fromCommand(
                            batch.adapterId(),
                            batch.deliveryQueueKey(),
                            batch.targetTransportNodeId(),
                            command,
                            endpoint,
                            DispatchOutcomeStatus.UNAVAILABLE,
                            true,
                            "delivery adapter is unavailable"
                    );
                    immediateOutcomes.add(outcome);
                    handleRetryableFailure(outcome);
                    continue;
                }
                AdapterDispatchRequest request = toRequest(batch, command, endpoint);
                itemByDeliveryId.put(request.deliveryId(), new ResolvedDispatchCommand(command, endpoint));
                groupedByAdapter.computeIfAbsent(adapter, ignored -> new ArrayList<>()).add(request);
            } catch (EndpointResolutionException e) {
                DispatchOutcome outcome = DispatchOutcome.fromCommand(
                        batch.adapterId(),
                        batch.deliveryQueueKey(),
                        batch.targetTransportNodeId(),
                        command,
                        e.endpoint(),
                        DispatchOutcomeStatus.NO_ENDPOINT,
                        true,
                        e.getMessage()
                );
                immediateOutcomes.add(outcome);
                handleRetryableFailure(outcome);
            } catch (RuntimeException e) {
                DispatchOutcome outcome = DispatchOutcome.fromCommand(
                        batch.adapterId(),
                        batch.deliveryQueueKey(),
                        batch.targetTransportNodeId(),
                        command,
                        endpoint,
                        DispatchOutcomeStatus.INVALID,
                        false,
                        e.getMessage()
                );
                immediateOutcomes.add(outcome);
                continue;
            }
        }

        List<AdapterDispatchGroup> groups = new ArrayList<>(groupedByAdapter.size());
        for (Map.Entry<WorkerAdapter, List<AdapterDispatchRequest>> entry : groupedByAdapter.entrySet()) {
            groups.add(new AdapterDispatchGroup(
                    batch.deliveryQueueKey(),
                    entry.getKey(),
                    Collections.unmodifiableList(entry.getValue())
            ));
        }

        List<DispatchOutcome> outcomes = new ArrayList<>(immediateOutcomes);
        for (DispatchGroupResult dispatchResult : dispatchGroups(groups)) {
            logDispatchOutcomes(dispatchResult.adapter(), dispatchResult.outcomes());
            for (DispatchOutcome outcome : dispatchResult.outcomes()) {
                outcomes.add(outcome);
                if (outcome == null || !outcome.isRetryable()) {
                    continue;
                }
                ResolvedDispatchCommand item = itemByDeliveryId.get(outcome.getDeliveryId());
                if (item != null) {
                    handleRetryableFailure(outcome);
                }
            }
        }
        return Collections.unmodifiableList(outcomes);
    }

    private WorkerAdapter resolveAdapter(String adapterId) {
        try {
            return transportRuntimeRegistry.resolveDispatchAdapterByAdapterId(adapterId);
        } catch (RuntimeException e) {
            logger.warn("Cannot resolve delivery adapter: adapterId={}, reason={}", adapterId, e.getMessage());
            return null;
        }
    }

    private AdapterEndpoint resolveEndpoint(DeliveryCommandBatch batch, DeliveryCommand command) {
        if (localTransportNodeId != null && !localTransportNodeId.equals(batch.targetTransportNodeId())) {
            throw new EndpointResolutionException("delivery command batch is not targeted to local transport node", null);
        }
        WorkerDispatchRouteOwner owner = routeOwnerView
                .activeOwnerForSelectedWorker(batch.adapterId(), command.getSelectedWorkerId())
                .orElseThrow(() -> new EndpointResolutionException("transport endpoint owner disappeared after handoff", null));
        AdapterEndpoint endpoint = new AdapterEndpoint(
                owner.routeKey(),
                owner.transportNodeId(),
                owner.connectionId(),
                owner.leaseExpireAtEpochMillis()
        );
        if (!batch.targetTransportNodeId().equals(endpoint.transportNodeId())) {
            throw new EndpointResolutionException("transport endpoint owner moved after handoff", endpoint);
        }
        if (localTransportNodeId != null && !localTransportNodeId.equals(endpoint.transportNodeId())) {
            throw new EndpointResolutionException("transport endpoint owner is not local", endpoint);
        }
        return endpoint;
    }

    private AdapterDispatchRequest toRequest(DeliveryCommandBatch batch,
                                             DeliveryCommand command,
                                             AdapterEndpoint endpoint) {
        return new AdapterDispatchRequest(
                command.getCommandId(),
                batch.adapterId(),
                command.getSelectedWorkerId(),
                command.getContent(),
                command.getExecutionContext(),
                endpoint,
                command.getCreatedAtEpochMillis()
        );
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
                logger.warn("Delivery executor rejected adapter batch: adapterId={}, requests={}, reason={}",
                        adapterId(group.adapter()), group.requests().size(), e.getMessage());
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
                results.add(dispatchRejectedGroup(group, "delivery executor rejected adapter batch"));
                continue;
            }
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results.add(dispatchRejectedGroup(group, "delivery executor interrupted while awaiting adapter batch"));
            } catch (ExecutionException e) {
                results.add(dispatchFailedGroup(group, "delivery adapter batch failed", e.getCause()));
            }
        }
        return results;
    }

    private DispatchGroupResult dispatchGroup(AdapterDispatchGroup group) {
        try {
            List<DispatchOutcome> outcomes = group.adapter().dispatch(group.requests());
            return new DispatchGroupResult(group.adapter(), outcomes == null ? List.of() : outcomes);
        } catch (RuntimeException e) {
            return dispatchFailedGroup(group, "delivery adapter batch failed", e);
        }
    }

    private DispatchGroupResult dispatchFailedGroup(AdapterDispatchGroup group, String message, Throwable error) {
        logger.error("{}: adapterId={}, requests={}", message, adapterId(group.adapter()), group.requests().size(), error);
        return new DispatchGroupResult(group.adapter(), adapterUnavailableOutcomes(group, error != null ? error.getMessage() : message));
    }

    private DispatchGroupResult dispatchRejectedGroup(AdapterDispatchGroup group, String reason) {
        logger.warn("{}: adapterId={}, requests={}", reason, adapterId(group.adapter()), group.requests().size());
        return new DispatchGroupResult(group.adapter(), adapterUnavailableOutcomes(group, reason));
    }

    private List<DispatchOutcome> adapterUnavailableOutcomes(AdapterDispatchGroup group, String reason) {
        List<DispatchOutcome> outcomes = new ArrayList<>(group.requests().size());
        for (AdapterDispatchRequest request : group.requests()) {
            outcomes.add(DispatchOutcome.unavailable(
                    adapterId(group.adapter()),
                    group.deliveryQueueKey(),
                    request,
                    reason
            ));
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
            if (outcome.getStatus() == DispatchOutcomeStatus.DELIVERED
                    || outcome.getStatus() == DispatchOutcomeStatus.QUEUED) {
                logger.debug("Transport delivery outcome: adapterId={}, deliveryQueueKey={}, deliveryId={}, attemptId={}, selectedWorkerId={}, status={}",
                        outcome.getAdapterId(), outcome.getDeliveryQueueKey(), outcome.getDeliveryId(),
                        outcome.getAttemptId(), outcome.getSelectedWorkerId(), outcome.getStatus());
                continue;
            }
            logger.warn("Transport delivery outcome: adapterId={}, deliveryQueueKey={}, deliveryId={}, attemptId={}, selectedWorkerId={}, status={}, retryable={}, reason={}, routedAdapter={}",
                    outcome.getAdapterId(), outcome.getDeliveryQueueKey(), outcome.getDeliveryId(),
                    outcome.getAttemptId(), outcome.getSelectedWorkerId(), outcome.getStatus(), outcome.isRetryable(),
                    outcome.getReason(), adapter != null ? adapter.adapterId() : null);
        }
    }

    private void handleRetryableFailure(DispatchOutcome outcome) {
        if (outcome == null || !outcome.isRetryable()) {
            return;
        }
        if (failureHandler == null) {
            logger.warn("Delivery failure has no failure handler: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), outcome.getReason());
            return;
        }
        boolean handled = failureHandler.handle(new TransportDeliveryFailureEvent(outcome, outcome.getReason()));
        if (!handled) {
            logger.error("Delivery failure was not handled: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), outcome.getReason());
        }
    }

    private static String adapterId(WorkerAdapter adapter) {
        return adapter != null ? adapter.adapterId() : null;
    }

    private static String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record AdapterDispatchGroup(String deliveryQueueKey,
                                        WorkerAdapter adapter,
                                        List<AdapterDispatchRequest> requests) {
    }

    private record ResolvedDispatchCommand(DeliveryCommand command, AdapterEndpoint endpoint) {
    }

    private static final class EndpointResolutionException extends RuntimeException {
        private final AdapterEndpoint endpoint;

        private EndpointResolutionException(String message, AdapterEndpoint endpoint) {
            super(message);
            this.endpoint = endpoint;
        }

        private AdapterEndpoint endpoint() {
            return endpoint;
        }
    }

    private record DispatchGroupResult(WorkerAdapter adapter, List<DispatchOutcome> outcomes) {
    }
}
