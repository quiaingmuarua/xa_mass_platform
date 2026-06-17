package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.lease.TransportEndpointLeaseViewRecord;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandBatch;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryCommandBatchListener;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureEvent;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureHandler;
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
 * Embedded Java bridge from transport-owned command batches to local adapter
 * command executors.
 */
public final class TransportDeliveryCommandListener implements TransportDeliveryCommandBatchListener {

    private static final Logger logger = LoggerFactory.getLogger(TransportDeliveryCommandListener.class);

    private final TransportRuntimeRegistry transportRuntimeRegistry;
    private final TransportEndpointLeaseStore endpointLeaseStore;
    private final TransportDeliveryFailureHandler failureHandler;
    private final RuntimeTaskExecutor runtimeTaskExecutor;

    public TransportDeliveryCommandListener(TransportRuntimeRegistry transportRuntimeRegistry,
                                            TransportEndpointLeaseStore endpointLeaseStore,
                                            TransportDeliveryFailureHandler failureHandler,
                                            RuntimeTaskExecutor runtimeTaskExecutor) {
        this.transportRuntimeRegistry = Objects.requireNonNull(transportRuntimeRegistry, "transportRuntimeRegistry");
        this.endpointLeaseStore = Objects.requireNonNull(endpointLeaseStore, "endpointLeaseStore");
        this.failureHandler = failureHandler;
        this.runtimeTaskExecutor = runtimeTaskExecutor;
    }

    @Override
    public List<DispatchOutcome> onDeliveryCommandBatch(DeliveryCommandBatch batch) {
        if (batch == null || batch.items().isEmpty()) {
            return List.of();
        }

        Map<String, List<DeliveryCommand>> groupedByAdapterId = new LinkedHashMap<>();
        Map<String, AdapterCommandExecutor> executorByAdapterId = new LinkedHashMap<>();
        Map<String, DeliveryCommand> itemByDeliveryId = new LinkedHashMap<>();
        List<DispatchOutcome> immediateOutcomes = new ArrayList<>();
        for (DeliveryCommand command : batch.items()) {
            try {
                TransportEndpointLeaseViewRecord endpoint = endpointLeaseStore
                        .currentEndpointLease(command.getDeliveryBucketId(), command.getSelectedWorkerId())
                        .orElse(null);
                if (endpoint == null) {
                    DispatchOutcome outcome = DispatchOutcome.fromCommand(
                            command,
                            DispatchOutcomeStatus.NO_ENDPOINT,
                            true,
                            "selected worker has no current endpoint lease"
                    );
                    immediateOutcomes.add(outcome);
                    handleRetryableFailure(outcome);
                    continue;
                }
                String adapterId = endpoint.endpointDriverId();
                TransportBinding binding = resolveBinding(adapterId);
                if (binding == null) {
                    DispatchOutcome outcome = DispatchOutcome.fromCommand(
                            command,
                            DispatchOutcomeStatus.UNAVAILABLE,
                            true,
                            "delivery adapter is unavailable"
                    );
                    immediateOutcomes.add(outcome);
                    handleRetryableFailure(outcome);
                    continue;
                }
                itemByDeliveryId.put(command.getCommandId(), command);
                groupedByAdapterId.computeIfAbsent(binding.getAdapterId(), ignored -> new ArrayList<>()).add(command);
                executorByAdapterId.putIfAbsent(binding.getAdapterId(), binding.getCommandExecutor());
            } catch (RuntimeException e) {
                DispatchOutcome outcome = DispatchOutcome.fromCommand(
                        command,
                        DispatchOutcomeStatus.INVALID,
                        false,
                        e.getMessage()
                );
                immediateOutcomes.add(outcome);
                continue;
            }
        }

        List<AdapterDispatchGroup> groups = new ArrayList<>(groupedByAdapterId.size());
        for (Map.Entry<String, List<DeliveryCommand>> entry : groupedByAdapterId.entrySet()) {
            groups.add(new AdapterDispatchGroup(
                    batch.deliveryQueueKey(),
                    entry.getKey(),
                    executorByAdapterId.get(entry.getKey()),
                    Collections.unmodifiableList(entry.getValue())
            ));
        }

        List<DispatchOutcome> outcomes = new ArrayList<>(immediateOutcomes);
        for (DispatchGroupResult dispatchResult : dispatchGroups(groups)) {
            logDispatchOutcomes(batch.deliveryQueueKey(), dispatchResult.adapterId(), dispatchResult.outcomes());
            for (DispatchOutcome outcome : dispatchResult.outcomes()) {
                outcomes.add(outcome);
                if (outcome == null || !outcome.isRetryable()) {
                    continue;
                }
                DeliveryCommand command = itemByDeliveryId.get(outcome.getDeliveryId());
                if (command != null) {
                    handleRetryableFailure(outcome);
                }
            }
        }
        return Collections.unmodifiableList(outcomes);
    }

    private TransportBinding resolveBinding(String adapterId) {
        try {
            return transportRuntimeRegistry.resolveBindingByAdapterId(adapterId);
        } catch (RuntimeException e) {
            logger.warn("Cannot resolve delivery adapter: adapterId={}, reason={}", adapterId, e.getMessage());
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
                logger.warn("Delivery executor rejected adapter batch: adapterId={}, requests={}, reason={}",
                        group.adapterId(), group.requests().size(), e.getMessage());
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
            List<DispatchOutcome> outcomes = group.executor().dispatch(group.requests());
            return new DispatchGroupResult(group.adapterId(), outcomes == null ? List.of() : outcomes);
        } catch (RuntimeException e) {
            return dispatchFailedGroup(group, "delivery adapter batch failed", e);
        }
    }

    private DispatchGroupResult dispatchFailedGroup(AdapterDispatchGroup group, String message, Throwable error) {
        logger.error("{}: adapterId={}, requests={}", message, group.adapterId(), group.requests().size(), error);
        return new DispatchGroupResult(group.adapterId(), adapterUnavailableOutcomes(group, error != null ? error.getMessage() : message));
    }

    private DispatchGroupResult dispatchRejectedGroup(AdapterDispatchGroup group, String reason) {
        logger.warn("{}: adapterId={}, requests={}", reason, group.adapterId(), group.requests().size());
        return new DispatchGroupResult(group.adapterId(), adapterUnavailableOutcomes(group, reason));
    }

    private List<DispatchOutcome> adapterUnavailableOutcomes(AdapterDispatchGroup group, String reason) {
        List<DispatchOutcome> outcomes = new ArrayList<>(group.requests().size());
        for (DeliveryCommand command : group.requests()) {
            outcomes.add(DispatchOutcome.unavailable(
                    command,
                    reason
            ));
        }
        return Collections.unmodifiableList(outcomes);
    }

    private void logDispatchOutcomes(String deliveryQueueKey, String adapterId, List<DispatchOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return;
        }
        for (DispatchOutcome outcome : outcomes) {
            if (outcome == null) {
                continue;
            }
            if (outcome.getStatus() == DispatchOutcomeStatus.DELIVERED
                    || outcome.getStatus() == DispatchOutcomeStatus.QUEUED) {
                logger.debug("Transport delivery outcome: adapterId={}, deliveryQueueKey={}, deliveryId={}, selectedWorkerId={}, status={}",
                        adapterId, deliveryQueueKey, outcome.getDeliveryId(),
                        outcome.getSelectedWorkerId(), outcome.getStatus());
                continue;
            }
            logger.warn("Transport delivery outcome: adapterId={}, deliveryQueueKey={}, deliveryId={}, selectedWorkerId={}, status={}, retryable={}, reason={}, routedAdapter={}",
                    adapterId, deliveryQueueKey, outcome.getDeliveryId(),
                    outcome.getSelectedWorkerId(), outcome.getStatus(), outcome.isRetryable(),
                    outcome.getReason(), adapterId);
        }
    }

    private void handleRetryableFailure(DispatchOutcome outcome) {
        if (outcome == null || !outcome.isRetryable()) {
            return;
        }
        if (failureHandler == null) {
            logger.warn("Delivery failure has no failure handler: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), outcome.getReason());
            throw new DeliveryFailureEmissionException("delivery failure has no failure handler");
        }
        boolean handled = failureHandler.handle(new TransportDeliveryFailureEvent(outcome, outcome.getReason()));
        if (!handled) {
            logger.error("Delivery failure was not handled: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), outcome.getReason());
            throw new DeliveryFailureEmissionException("delivery failure was not handled");
        }
    }

    private record AdapterDispatchGroup(String deliveryQueueKey,
                                        String adapterId,
                                        AdapterCommandExecutor executor,
                                        List<DeliveryCommand> requests) {
    }

    private static final class DeliveryFailureEmissionException extends RuntimeException {
        private DeliveryFailureEmissionException(String message) {
            super(message);
        }
    }

    private record DispatchGroupResult(String adapterId, List<DispatchOutcome> outcomes) {
    }
}
