package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.runtime.packet.TransportPacketFactory;
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
    private final TransportDeliveryFailureHandler failureHandler;
    private final RuntimeTaskExecutor runtimeTaskExecutor;
    private final TransportPacketFactory packetFactory = new TransportPacketFactory();

    public TransportDeliveryCommandListener(TransportRuntimeRegistry transportRuntimeRegistry,
                                            TransportDeliveryFailureHandler failureHandler,
                                            RuntimeTaskExecutor runtimeTaskExecutor) {
        this.transportRuntimeRegistry = Objects.requireNonNull(transportRuntimeRegistry, "transportRuntimeRegistry");
        this.failureHandler = failureHandler;
        this.runtimeTaskExecutor = runtimeTaskExecutor;
    }

    public List<DispatchOutcome> onDeliveryCommandBatch(DeliveryCommandBatch batch) {
        if (batch == null || batch.items().isEmpty()) {
            return List.of();
        }

        DeliveryObservationGroupContext groupContext = DeliveryObservationGroupContext.now(
                batch.adapterId(),
                batch.deliveryQueueKey(),
                batch.targetTransportNodeId()
        );
        WorkerAdapter adapter = resolveAdapter(batch.adapterId());
        if (adapter == null) {
            List<DispatchOutcome> outcomes = new ArrayList<>(batch.items().size());
            for (ResolvedDeliveryItem item : batch.items()) {
                DispatchOutcome outcome = DeliveryObservationSupport.outcome(
                        groupContext,
                        item.command(),
                        item.endpoint(),
                        DispatchOutcomeStatus.UNAVAILABLE,
                        true,
                        "delivery adapter is unavailable"
                );
                outcomes.add(outcome);
                handleRetryableFailure(groupContext, item, outcome);
            }
            return Collections.unmodifiableList(outcomes);
        }

        Map<WorkerAdapter, List<TransportDispatchEnvelope>> groupedByAdapter = new LinkedHashMap<>();
        Map<String, ResolvedDeliveryItem> itemByDeliveryId = new LinkedHashMap<>();
        List<DispatchOutcome> immediateOutcomes = new ArrayList<>();
        for (ResolvedDeliveryItem item : batch.items()) {
            TransportDispatchEnvelope envelope;
            try {
                envelope = toEnvelope(batch, item);
            } catch (RuntimeException e) {
                DispatchOutcome outcome = DeliveryObservationSupport.outcome(
                        groupContext,
                        item.command(),
                        item.endpoint(),
                        DispatchOutcomeStatus.INVALID,
                        false,
                        e.getMessage()
                );
                immediateOutcomes.add(outcome);
                continue;
            }
            itemByDeliveryId.put(envelope.getDeliveryId(), item);
            groupedByAdapter.computeIfAbsent(adapter, ignored -> new ArrayList<>()).add(envelope);
        }

        List<AdapterDispatchGroup> groups = new ArrayList<>(groupedByAdapter.size());
        for (Map.Entry<WorkerAdapter, List<TransportDispatchEnvelope>> entry : groupedByAdapter.entrySet()) {
            groups.add(new AdapterDispatchGroup(groupContext, entry.getKey(), Collections.unmodifiableList(entry.getValue())));
        }

        List<DispatchOutcome> outcomes = new ArrayList<>(immediateOutcomes);
        for (DispatchGroupResult dispatchResult : dispatchGroups(groups)) {
            logDispatchOutcomes(dispatchResult.adapter(), dispatchResult.outcomes());
            for (DispatchOutcome outcome : dispatchResult.outcomes()) {
                outcomes.add(outcome);
                if (outcome == null || !outcome.isRetryable()) {
                    continue;
                }
                ResolvedDeliveryItem item = itemByDeliveryId.get(outcome.getDeliveryId());
                if (item != null) {
                    handleRetryableFailure(groupContext, item, outcome);
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

    private TransportDispatchEnvelope toEnvelope(DeliveryCommandBatch batch, ResolvedDeliveryItem item) {
        DeliveryCommand command = item.command();
        EndpointLease endpoint = item.endpoint();
        TransportPacket packet = packetFactory.fromDispatchContent(
                command.getCommandId(),
                batch.adapterId(),
                endpoint.routeKey(),
                null,
                command.getSelectedWorkerId(),
                command.getContent(),
                command.getExecutionContext()
        );
        return new TransportDispatchEnvelope(
                command.getCommandId(),
                command.getSelectedWorkerId(),
                packet,
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
                logger.warn("Delivery executor rejected adapter batch: adapterId={}, envelopes={}, reason={}",
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
            List<DispatchOutcome> outcomes = group.adapter().dispatchEnvelopes(group.envelopes());
            return new DispatchGroupResult(group.adapter(), outcomes == null ? List.of() : outcomes);
        } catch (RuntimeException e) {
            return dispatchFailedGroup(group, "delivery adapter batch failed", e);
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
            outcomes.add(DispatchOutcome.unavailable(
                    adapterId(group.adapter()),
                    group.groupContext().deliveryQueueKey(),
                    envelope,
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

    private void handleRetryableFailure(DeliveryObservationGroupContext groupContext,
                                        ResolvedDeliveryItem item,
                                        DispatchOutcome outcome) {
        if (item == null || outcome == null || !outcome.isRetryable()) {
            return;
        }
        if (failureHandler == null) {
            logger.warn("Delivery failure has no failure handler: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), outcome.getReason());
            return;
        }
        boolean handled = failureHandler.handle(DeliveryObservationSupport.failure(
                groupContext,
                item.command(),
                item.endpoint(),
                outcome,
                outcome.getReason()
        ));
        if (!handled) {
            logger.error("Delivery failure was not handled: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), outcome.getReason());
        }
    }

    private static String adapterId(WorkerAdapter adapter) {
        return adapter != null ? adapter.adapterId() : null;
    }

    private record AdapterDispatchGroup(DeliveryObservationGroupContext groupContext,
                                        WorkerAdapter adapter,
                                        List<TransportDispatchEnvelope> envelopes) {
    }

    private record DispatchGroupResult(WorkerAdapter adapter, List<DispatchOutcome> outcomes) {
    }
}
