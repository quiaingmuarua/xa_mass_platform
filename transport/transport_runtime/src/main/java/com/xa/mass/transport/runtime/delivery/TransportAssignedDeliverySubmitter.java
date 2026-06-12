package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.route.WorkerDispatchRouteOwner;
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

    public List<DispatchOutcome> submit(DeliveryCommandGroup group) {
        if (group == null) {
            return List.of();
        }
        return submit(List.of(group));
    }

    public List<DispatchOutcome> submit(List<DeliveryCommandGroup> commandGroups) {
        if (commandGroups == null || commandGroups.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>();
        for (DeliveryCommandGroup commandGroup : commandGroups) {
            if (commandGroup != null) {
                outcomes.addAll(submitGroup(commandGroup));
            }
        }
        return Collections.unmodifiableList(outcomes);
    }

    private List<DispatchOutcome> submitGroup(DeliveryCommandGroup commandGroup) {
        String adapterId = commandGroup.adapterId();
        String deliveryQueueKey = deliveryQueueKey(adapterId);
        Map<String, CommandGroup> groups = new LinkedHashMap<>();
        List<DispatchOutcome> outcomes = new ArrayList<>();

        for (DeliveryCommand command : commandGroup.commands()) {
            Optional<WorkerDispatchRouteOwner> selectedOwner =
                    routeOwnerView.activeOwnerForSelectedWorker(adapterId, command.getSelectedWorkerId());
            if (selectedOwner.isEmpty()) {
                DeliveryObservationGroupContext groupContext =
                        DeliveryObservationGroupContext.now(adapterId, deliveryQueueKey, null);
                DispatchOutcome outcome = DeliveryObservationSupport.outcome(
                        groupContext,
                        command,
                        null,
                        DispatchOutcomeStatus.NO_ENDPOINT,
                        true,
                        MISSING_OWNER_REASON
                );
                outcomes.add(handleRetryableFailure(groupContext, command, null, outcome, MISSING_OWNER_REASON));
                continue;
            }

            WorkerDispatchRouteOwner owner = selectedOwner.get();
            EndpointLease endpoint = EndpointLease.fromOwner(owner, command.getSelectedWorkerId());
            DeliveryObservationGroupContext groupContext =
                    DeliveryObservationGroupContext.now(adapterId, deliveryQueueKey, endpoint.transportNodeId());
            if (!isNodeUsable(owner)) {
                DispatchOutcome outcome = DeliveryObservationSupport.outcome(
                        groupContext,
                        command,
                        endpoint,
                        DispatchOutcomeStatus.NO_ENDPOINT,
                        true,
                        UNAVAILABLE_NODE_REASON
                );
                outcomes.add(handleRetryableFailure(groupContext, command, endpoint, outcome, UNAVAILABLE_NODE_REASON));
                continue;
            }

            String groupKey = deliveryQueueKey + "\n" + endpoint.transportNodeId();
            CommandGroup group = groups.computeIfAbsent(
                    groupKey,
                    ignored -> new CommandGroup(adapterId, deliveryQueueKey, endpoint.transportNodeId())
            );
            group.items.add(new ResolvedDeliveryItem(command, endpoint));
        }

        for (CommandGroup group : groups.values()) {
            DeliveryCommandBatch batch = new DeliveryCommandBatch(
                    group.adapterId,
                    group.deliveryQueueKey,
                    group.targetTransportNodeId,
                    group.items
            );
            Map<String, ResolvedDeliveryItem> itemById = new LinkedHashMap<>();
            for (ResolvedDeliveryItem item : group.items) {
                itemById.put(item.command().getCommandId(), item);
            }
            DeliveryObservationGroupContext groupContext =
                    DeliveryObservationGroupContext.now(group.adapterId, group.deliveryQueueKey, group.targetTransportNodeId);
            for (DispatchOutcome outcome : handoff.offer(batch)) {
                if (outcome == null) {
                    continue;
                }
                outcomes.add(outcome);
                if (!outcome.isRetryable()) {
                    continue;
                }
                ResolvedDeliveryItem item = itemById.get(outcome.getDeliveryId());
                if (item != null) {
                    handleRetryableFailure(groupContext, item.command(), item.endpoint(), outcome, outcome.getReason());
                }
            }
        }
        return outcomes;
    }

    private boolean isNodeUsable(WorkerDispatchRouteOwner owner) {
        if (owner == null || owner.transportNodeId() == null || owner.transportNodeId().isBlank()) {
            return false;
        }
        if (transportNodeRegistry == null) {
            return true;
        }
        try {
            return transportNodeRegistry.isNodeOnline(owner.transportNodeId());
        } catch (RuntimeException e) {
            logger.warn("Cannot verify transport node owner: transportNodeId={}, reason={}",
                    owner.transportNodeId(), e.getMessage());
            return false;
        }
    }

    private DispatchOutcome handleRetryableFailure(DeliveryObservationGroupContext groupContext,
                                                   DeliveryCommand command,
                                                   EndpointLease endpoint,
                                                   DispatchOutcome outcome,
                                                   String detail) {
        if (command == null || outcome == null || !outcome.isRetryable()) {
            return outcome;
        }
        if (failureHandler == null) {
            logger.warn("Delivery failure has no failure handler: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), detail);
            return outcome;
        }
        boolean handled = failureHandler.handle(DeliveryObservationSupport.failure(
                groupContext,
                command,
                endpoint,
                outcome,
                detail
        ));
        if (!handled) {
            logger.error("Delivery failure was not handled: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), detail);
        }
        return outcome;
    }

    private static String deliveryQueueKey(String adapterId) {
        return adapterId;
    }

    private static final class CommandGroup {
        private final String adapterId;
        private final String deliveryQueueKey;
        private final String targetTransportNodeId;
        private final List<ResolvedDeliveryItem> items = new ArrayList<>();

        private CommandGroup(String adapterId, String deliveryQueueKey, String targetTransportNodeId) {
            this.adapterId = adapterId;
            this.deliveryQueueKey = deliveryQueueKey;
            this.targetTransportNodeId = targetTransportNodeId;
        }
    }
}
