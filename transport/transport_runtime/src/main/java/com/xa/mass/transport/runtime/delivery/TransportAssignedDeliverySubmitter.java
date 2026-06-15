package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.route.SelectedWorkerDeliveryTarget;
import com.xa.mass.transport.route.WorkerDispatchRouteOwnerView;
import com.xa.mass.transport.runtime.node.TransportNodeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Transport-owned final-hop submitter for commands already assigned to a
 * selected worker by the scheduling plane.
 */
public final class TransportAssignedDeliverySubmitter {

    private static final Logger logger = LoggerFactory.getLogger(TransportAssignedDeliverySubmitter.class);
    private static final String MISSING_OWNER_REASON = "transport endpoint owner is unavailable after assignment";
    private static final String UNAVAILABLE_NODE_REASON = "transport node is unavailable after assignment";

    private final TransportDeliveryCommandHandoff handoff;
    private final WorkerDispatchRouteOwnerView routeOwnerView;
    private final TransportNodeRegistry transportNodeRegistry;
    private final TransportDeliveryFailureHandler failureHandler;

    public TransportAssignedDeliverySubmitter(TransportDeliveryCommandHandoff handoff,
                                              WorkerDispatchRouteOwnerView routeOwnerView,
                                              TransportNodeRegistry transportNodeRegistry,
                                              TransportDeliveryFailureHandler failureHandler) {
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.routeOwnerView = Objects.requireNonNull(routeOwnerView, "routeOwnerView");
        this.transportNodeRegistry = transportNodeRegistry;
        this.failureHandler = failureHandler;
    }

    public List<DispatchOutcome> submit(List<DeliveryCommand> commands) {
        Objects.requireNonNull(commands, "commands");
        if (commands.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>();
        Map<String, DeliveryBatchBuilder> batches = new LinkedHashMap<>();

        for (DeliveryCommand command : commands) {
            DeliveryCommand normalizedCommand = Objects.requireNonNull(command, "command");
            Optional<SelectedWorkerDeliveryTarget> selectedTarget =
                    routeOwnerView.targetForSelectedWorker(
                            normalizedCommand.getDeliveryBucketId(),
                            normalizedCommand.getSelectedWorkerId()
                    );
            if (selectedTarget.isEmpty()) {
                DispatchOutcome outcome = DispatchOutcome.fromCommand(
                        normalizedCommand,
                        DispatchOutcomeStatus.NO_ENDPOINT,
                        true,
                        MISSING_OWNER_REASON
                );
                outcomes.add(handleRetryableFailure(outcome, MISSING_OWNER_REASON));
                continue;
            }

            if (!isNodeUsable(selectedTarget.get())) {
                DispatchOutcome outcome = DispatchOutcome.fromCommand(
                        normalizedCommand,
                        DispatchOutcomeStatus.NO_ENDPOINT,
                        true,
                        UNAVAILABLE_NODE_REASON
                );
                outcomes.add(handleRetryableFailure(outcome, UNAVAILABLE_NODE_REASON));
                continue;
            }

            String deliveryLaneKey = deliveryLaneKey(normalizedCommand.getDeliveryBucketId());
            String groupKey = deliveryLaneKey + "\n" + selectedTarget.get().targetTransportNodeId();
            DeliveryBatchBuilder batch = batches.computeIfAbsent(
                    groupKey,
                    ignored -> new DeliveryBatchBuilder(
                            normalizedCommand.getDeliveryBucketId(),
                            deliveryLaneKey,
                            selectedTarget.get().targetTransportNodeId()
                    )
            );
            batch.items.add(normalizedCommand);
        }

        for (DeliveryBatchBuilder builder : batches.values()) {
            DeliveryCommandBatch batch = builder.toBatch();
            List<DispatchOutcome> offeredOutcomes = offerBatch(batch);
            outcomes.addAll(offeredOutcomes);
            handleRetryableFailures(offeredOutcomes);
        }
        return Collections.unmodifiableList(outcomes);
    }

    private List<DispatchOutcome> offerBatch(DeliveryCommandBatch batch) {
        try {
            List<DispatchOutcome> offered = handoff.offer(batch);
            if (offered == null || offered.isEmpty()) {
                return List.of();
            }
            return offered.stream()
                    .filter(Objects::nonNull)
                    .toList();
        } catch (RuntimeException e) {
            logger.warn("Delivery command handoff offer failed: deliveryBucketId={}, deliveryLaneKey={}, targetTransportNodeId={}, items={}, reason={}",
                    batch.deliveryBucketId(), batch.deliveryLaneKey(), batch.targetTransportNodeId(),
                    batch.items().size(), e.getMessage());
            String reason = e.getMessage() == null || e.getMessage().isBlank()
                    ? "delivery command handoff offer failed"
                    : "delivery command handoff offer failed: " + e.getMessage();
            return batch.items().stream()
                    .map(item -> DispatchOutcome.fromCommand(
                            item,
                            DispatchOutcomeStatus.UNAVAILABLE,
                            true,
                            reason))
                    .toList();
        }
    }

    private void handleRetryableFailures(List<DispatchOutcome> outcomes) {
        for (DispatchOutcome outcome : outcomes) {
            if (outcome != null && outcome.isRetryable()) {
                handleRetryableFailure(outcome, outcome.getReason());
            }
        }
    }

    private boolean isNodeUsable(SelectedWorkerDeliveryTarget target) {
        if (target == null
                || target.targetTransportNodeId() == null
                || target.targetTransportNodeId().isBlank()) {
            return false;
        }
        if (transportNodeRegistry == null) {
            return true;
        }
        try {
            return transportNodeRegistry.isNodeOnline(target.targetTransportNodeId());
        } catch (RuntimeException e) {
            logger.warn("Cannot verify transport node owner: transportNodeId={}, reason={}",
                    target.targetTransportNodeId(), e.getMessage());
            return false;
        }
    }

    private DispatchOutcome handleRetryableFailure(DispatchOutcome outcome,
                                                   String detail) {
        if (outcome == null || !outcome.isRetryable()) {
            return outcome;
        }
        if (failureHandler == null) {
            logger.warn("Delivery failure has no failure handler: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), detail);
            return outcome;
        }
        boolean handled = failureHandler.handle(new TransportDeliveryFailureEvent(outcome, detail));
        if (!handled) {
            logger.error("Delivery failure was not handled: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), detail);
        }
        return outcome;
    }

    private static String deliveryLaneKey(String deliveryBucketId) {
        return deliveryBucketId;
    }

    private static final class DeliveryBatchBuilder {
        private final String deliveryBucketId;
        private final String deliveryLaneKey;
        private final String targetTransportNodeId;
        private final List<DeliveryCommand> items = new ArrayList<>();

        private DeliveryBatchBuilder(String deliveryBucketId, String deliveryLaneKey, String targetTransportNodeId) {
            this.deliveryBucketId = deliveryBucketId;
            this.deliveryLaneKey = deliveryLaneKey;
            this.targetTransportNodeId = targetTransportNodeId;
        }

        private DeliveryCommandBatch toBatch() {
            return new DeliveryCommandBatch(deliveryBucketId, deliveryLaneKey, targetTransportNodeId, items);
        }
    }
}
