package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.packet.TransportPacket;
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

    public List<DispatchOutcome> submit(List<DeliveryCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        Map<String, CommandGroup> groups = new LinkedHashMap<>();
        List<DispatchOutcome> outcomes = new ArrayList<>();

        for (DeliveryCommand command : commands) {
            if (command == null) {
                continue;
            }
            Optional<WorkerDispatchRouteOwner> selectedOwner =
                    routeOwnerView.activeOwnerForSelectedWorker(command.getAdapterId(), command.getSelectedWorkerId());
            if (selectedOwner.isEmpty()) {
                outcomes.add(handleRetryableFailure(
                        command,
                        DispatchOutcome.noEndpoint(command, MISSING_OWNER_REASON),
                        MISSING_OWNER_REASON
                ));
                continue;
            }
            WorkerDispatchRouteOwner owner = selectedOwner.get();
            if (!isNodeUsable(owner)) {
                outcomes.add(handleRetryableFailure(
                        command,
                        DispatchOutcome.noEndpoint(command, UNAVAILABLE_NODE_REASON),
                        UNAVAILABLE_NODE_REASON
                ));
                continue;
            }

            DeliveryCommand resolved = withDeliveryOwner(command, owner);
            String groupKey = resolved.getDeliveryQueueKey() + "\n" + resolved.getTargetTransportNodeId();
            CommandGroup group = groups.computeIfAbsent(
                    groupKey,
                    ignored -> new CommandGroup(resolved.getDeliveryQueueKey(), resolved.getTargetTransportNodeId())
            );
            group.commands.add(resolved);
        }

        for (CommandGroup group : groups.values()) {
            DeliveryCommandBatch batch = new DeliveryCommandBatch(
                    group.deliveryQueueKey,
                    group.targetTransportNodeId,
                    group.commands
            );
            Map<String, DeliveryCommand> commandById = new LinkedHashMap<>();
            for (DeliveryCommand command : group.commands) {
                commandById.put(command.getCommandId(), command);
            }
            for (DispatchOutcome outcome : handoff.offer(batch)) {
                if (outcome == null) {
                    continue;
                }
                outcomes.add(outcome);
                if (!outcome.isRetryable()) {
                    continue;
                }
                handleRetryableFailure(commandById.get(outcome.getDeliveryId()), outcome, outcome.getReason());
            }
        }
        return Collections.unmodifiableList(outcomes);
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

    private DeliveryCommand withDeliveryOwner(DeliveryCommand command, WorkerDispatchRouteOwner owner) {
        String routeKey = firstNonBlank(command.getRouteKey(), owner.routeKey());
        TransportPacket payload = command.getPayload();
        if (routeKey != null && (payload.routeKey() == null || !routeKey.equals(payload.routeKey()))) {
            payload = payload.withTransportAddress(command.getAdapterId(), routeKey);
        }
        return new DeliveryCommand(
                command.getCommandId(),
                command.getAdapterId(),
                command.getSelectedWorkerId(),
                command.getDeliveryQueueKey(),
                owner.transportNodeId(),
                routeKey,
                owner.connectionId(),
                payload,
                command.getCorrelation(),
                command.getDeadlineEpochMillis(),
                command.getCreatedAtEpochMillis()
        );
    }

    private DispatchOutcome handleRetryableFailure(DeliveryCommand command, DispatchOutcome outcome, String detail) {
        if (command == null || outcome == null || !outcome.isRetryable()) {
            return outcome;
        }
        if (failureHandler == null) {
            logger.warn("Delivery failure has no failure handler: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), detail);
            return outcome;
        }
        boolean handled = failureHandler.handle(command, outcome, detail);
        if (!handled) {
            logger.error("Delivery failure was not handled: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), detail);
        }
        return outcome;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    private static final class CommandGroup {
        private final String deliveryQueueKey;
        private final String targetTransportNodeId;
        private final List<DeliveryCommand> commands = new ArrayList<>();

        private CommandGroup(String deliveryQueueKey, String targetTransportNodeId) {
            this.deliveryQueueKey = deliveryQueueKey;
            this.targetTransportNodeId = targetTransportNodeId;
        }
    }
}
