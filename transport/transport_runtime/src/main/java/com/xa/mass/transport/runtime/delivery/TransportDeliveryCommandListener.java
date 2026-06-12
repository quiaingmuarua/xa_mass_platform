package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.packet.PacketType;
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
    private final TransportDeliveryFailureHandler failureHandler;
    private final RuntimeTaskExecutor runtimeTaskExecutor;

    public TransportDeliveryCommandListener(TransportRuntimeRegistry transportRuntimeRegistry,
                                            TransportDeliveryFailureHandler failureHandler,
                                            RuntimeTaskExecutor runtimeTaskExecutor) {
        this.transportRuntimeRegistry = Objects.requireNonNull(transportRuntimeRegistry, "transportRuntimeRegistry");
        this.failureHandler = failureHandler;
        this.runtimeTaskExecutor = runtimeTaskExecutor;
    }

    public List<DispatchOutcome> onDeliveryCommandBatch(DeliveryCommandBatch batch) {
        if (batch == null || batch.commands().isEmpty()) {
            return List.of();
        }
        Map<WorkerAdapter, List<TransportDispatchEnvelope>> groupedByAdapter = new LinkedHashMap<>();
        Map<String, DeliveryCommand> commandByDeliveryId = new LinkedHashMap<>();
        List<DispatchOutcome> immediateOutcomes = new ArrayList<>();
        for (DeliveryCommand command : batch.commands()) {
            WorkerAdapter adapter = resolveAdapter(command.getAdapterId());
            if (adapter == null) {
                DispatchOutcome outcome = DispatchOutcome.unavailable(command, "delivery adapter is unavailable");
                immediateOutcomes.add(outcome);
                handleRetryableFailure(command, outcome);
                continue;
            }
            TransportDispatchEnvelope envelope;
            try {
                envelope = toEnvelope(command);
            } catch (RuntimeException e) {
                DispatchOutcome outcome = DispatchOutcome.invalid(command, e.getMessage());
                immediateOutcomes.add(outcome);
                continue;
            }
            commandByDeliveryId.put(envelope.getDeliveryId(), command);
            groupedByAdapter.computeIfAbsent(adapter, ignored -> new ArrayList<>()).add(envelope);
        }

        List<AdapterDispatchGroup> groups = new ArrayList<>(groupedByAdapter.size());
        for (Map.Entry<WorkerAdapter, List<TransportDispatchEnvelope>> entry : groupedByAdapter.entrySet()) {
            groups.add(new AdapterDispatchGroup(entry.getKey(), Collections.unmodifiableList(entry.getValue())));
        }

        List<DispatchOutcome> outcomes = new ArrayList<>(immediateOutcomes);
        for (DispatchGroupResult dispatchResult : dispatchGroups(groups)) {
            logDispatchOutcomes(dispatchResult.adapter(), dispatchResult.outcomes());
            for (DispatchOutcome outcome : dispatchResult.outcomes()) {
                outcomes.add(outcome);
                if (outcome == null || !outcome.isRetryable()) {
                    continue;
                }
                DeliveryCommand command = commandByDeliveryId.get(outcome.getDeliveryId());
                handleRetryableFailure(command, outcome);
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

    private TransportDispatchEnvelope toEnvelope(DeliveryCommand command) {
        if (command.getPayload().type() != PacketType.TASK_DISPATCH) {
            throw new IllegalArgumentException("delivery command payload must be TASK_DISPATCH");
        }
        return new TransportDispatchEnvelope(
                command.getCommandId(),
                command.getDeliveryQueueKey(),
                command.getSelectedWorkerId(),
                command.getPayload(),
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
            outcomes.add(DispatchOutcome.unavailable(adapterId(group.adapter()), envelope, reason));
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

    private void handleRetryableFailure(DeliveryCommand command, DispatchOutcome outcome) {
        if (command == null || outcome == null || !outcome.isRetryable()) {
            return;
        }
        if (failureHandler == null) {
            logger.warn("Delivery failure has no failure handler: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), outcome.getReason());
            return;
        }
        boolean handled = failureHandler.handle(command, outcome, outcome.getReason());
        if (!handled) {
            logger.error("Delivery failure was not handled: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), outcome.getReason());
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
