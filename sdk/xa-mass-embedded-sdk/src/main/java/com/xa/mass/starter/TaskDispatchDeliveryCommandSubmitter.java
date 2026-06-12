package com.xa.mass.starter;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.route.WorkerDispatchRouteOwner;
import com.xa.mass.transport.route.WorkerDispatchRouteOwnerView;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandBatch;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryCommandHandoff;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureHandler;
import com.xa.mass.transport.runtime.dispatch.AdapterDispatchLane;
import com.xa.mass.transport.runtime.node.TransportNodeRegistry;
import com.xa.mass.transport.runtime.packet.TransportPacketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Starter-owned translator from engine assignment bindings to transport
 * delivery commands.
 */
final class TaskDispatchDeliveryCommandSubmitter implements TaskDispatchBatchListener {

    private static final Logger logger = LoggerFactory.getLogger(TaskDispatchDeliveryCommandSubmitter.class);

    private final TransportDeliveryCommandHandoff handoff;
    private final BiFunction<TaskDispatchContext, TaskDispatchBinding, String> routeKeyFactory;
    private final WorkerDispatchRouteOwnerView routeOwnerView;
    private final TransportNodeRegistry transportNodeRegistry;
    private final TransportDeliveryFailureHandler failureHandler;
    private final TransportPacketFactory packetFactory = new TransportPacketFactory();

    TaskDispatchDeliveryCommandSubmitter(TransportDeliveryCommandHandoff handoff,
                                         BiFunction<TaskDispatchContext, TaskDispatchBinding, String> routeKeyFactory,
                                         WorkerDispatchRouteOwnerView routeOwnerView,
                                         TransportNodeRegistry transportNodeRegistry,
                                         TransportDeliveryFailureHandler failureHandler) {
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.routeKeyFactory = Objects.requireNonNull(routeKeyFactory, "routeKeyFactory");
        this.routeOwnerView = Objects.requireNonNull(routeOwnerView, "routeOwnerView");
        this.transportNodeRegistry = transportNodeRegistry;
        this.failureHandler = failureHandler;
    }

    @Override
    public void onTaskDispatchBatch(TaskDispatchContext task, List<TaskDispatchBinding> dispatchBindings) {
        if (task == null || dispatchBindings == null || dispatchBindings.isEmpty()) {
            return;
        }
        Map<String, CommandGroup> groups = new LinkedHashMap<>();
        List<TaskDispatchBinding> invalidBindings = new ArrayList<>();
        for (TaskDispatchBinding binding : dispatchBindings) {
            if (binding == null) {
                continue;
            }
            String adapterId = normalize(binding.adapterId());
            String selectedWorkerId = normalize(binding.workerId());
            if (adapterId == null || selectedWorkerId == null) {
                invalidBindings.add(binding);
                continue;
            }
            String routeKey = resolveRouteKey(task, binding);
            Optional<WorkerDispatchRouteOwner> selectedOwner = selectDeliveryOwner(adapterId, selectedWorkerId);
            if (selectedOwner.isEmpty()) {
                DeliveryCommand command = toCommand(task, binding, adapterId, selectedWorkerId, null, routeKey);
                handleDeliveryFailure(
                        command,
                        DispatchOutcome.noEndpoint(
                                command,
                                "transport endpoint is unavailable after assignment"
                        ),
                        "transport endpoint is unavailable after assignment"
                );
                continue;
            }

            WorkerDispatchRouteOwner owner = selectedOwner.get();
            AdapterDispatchLane adapterLane = AdapterDispatchLane.forTransportNode(adapterId, owner.transportNodeId());
            DeliveryCommand command = toCommand(task, binding, adapterId, selectedWorkerId, adapterLane, routeKey);
            String groupKey = command.getDeliveryQueueKey() + "\n" + command.getTargetTransportNodeId();
            CommandGroup group = groups.computeIfAbsent(
                    groupKey,
                    ignored -> new CommandGroup(command.getDeliveryQueueKey(), command.getTargetTransportNodeId())
            );
            group.commands.add(command);
        }

        compensateInvalidBindings(task, invalidBindings, "delivery command translation failed before transport handoff");

        for (CommandGroup group : groups.values()) {
            DeliveryCommandBatch batch = new DeliveryCommandBatch(
                    group.deliveryQueueKey,
                    group.targetTransportNodeId,
                    group.commands
            );
            for (DispatchOutcome outcome : handoff.offer(batch)) {
                if (outcome == null || !outcome.isRetryable()) {
                    continue;
                }
                DeliveryCommand command = group.commands.stream()
                        .filter(candidate -> candidate.getCommandId().equals(outcome.getDeliveryId()))
                        .findFirst()
                        .orElse(null);
                handleDeliveryFailure(command, outcome, outcome.getReason());
            }
        }
    }

    private DeliveryCommand toCommand(TaskDispatchContext task,
                                      TaskDispatchBinding binding,
                                      String adapterId,
                                      String selectedWorkerId,
                                      AdapterDispatchLane adapterLane,
                                      String routeKey) {
        TaskDispatchItem dispatchItem = TaskDispatchItem.from(task, binding);
        String commandId = UUID.randomUUID().toString();
        String deliveryQueueKey = adapterId;
        String targetTransportNodeId = adapterLane != null ? adapterLane.lanePartition() : null;
        return new DeliveryCommand(
                commandId,
                adapterId,
                selectedWorkerId,
                deliveryQueueKey,
                targetTransportNodeId,
                routeKey,
                null,
                packetFactory.fromDispatchView(commandId, adapterId, routeKey, null, dispatchItem),
                correlation(task, binding),
                0L,
                System.currentTimeMillis()
        );
    }

    private Map<String, String> correlation(TaskDispatchContext task, TaskDispatchBinding binding) {
        Map<String, String> values = new LinkedHashMap<>();
        put(values, "taskId", task.taskId());
        put(values, "messageId", binding.messageId());
        put(values, "attemptId", binding.attemptId());
        put(values, "attemptNo", Integer.toString(binding.attemptNo()));
        return values;
    }

    private String resolveRouteKey(TaskDispatchContext task, TaskDispatchBinding binding) {
        try {
            return normalize(routeKeyFactory.apply(
                    task,
                    binding
            ));
        } catch (RuntimeException e) {
            logger.warn("Failed to resolve routeKey for delivery command: taskId={}, messageId={}, reason={}",
                    task != null ? task.taskId() : null,
                    binding != null ? binding.messageId() : null,
                    e.getMessage());
            return null;
        }
    }

    private Optional<WorkerDispatchRouteOwner> selectDeliveryOwner(String adapterId, String selectedWorkerId) {
        return routeOwnerView.activeOwnerForSelectedWorker(adapterId, selectedWorkerId)
                .filter(this::isNodeUsable);
    }

    private boolean isNodeUsable(WorkerDispatchRouteOwner owner) {
        return owner != null
                && owner.transportNodeId() != null
                && !owner.transportNodeId().isBlank()
                && (transportNodeRegistry == null || transportNodeRegistry.isNodeOnline(owner.transportNodeId()));
    }

    private void handleDeliveryFailure(DeliveryCommand command, DispatchOutcome outcome, String detail) {
        if (command == null || outcome == null || !outcome.isRetryable()) {
            return;
        }
        if (failureHandler == null) {
            logger.warn("Cannot compensate delivery failure because no failure handler is configured: deliveryId={}, selectedWorkerId={}, detail={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), detail);
            return;
        }
        boolean handled = failureHandler.handle(command, outcome, detail);
        if (!handled) {
            logger.error("Delivery failure was not compensated: deliveryId={}, selectedWorkerId={}, detail={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), detail);
        }
    }

    private void compensateInvalidBindings(TaskDispatchContext task, List<TaskDispatchBinding> bindings, String detail) {
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        if (failureHandler == null) {
            logger.warn("Cannot compensate invalid delivery bindings because no failure handler is configured: taskId={}, bindings={}, detail={}",
                    task != null ? task.taskId() : null, bindings.size(), detail);
            return;
        }
        for (TaskDispatchBinding binding : bindings) {
            String adapterId = normalize(binding.adapterId());
            String selectedWorkerId = normalize(binding.workerId());
            if (adapterId == null) {
                adapterId = "unknown";
            }
            if (selectedWorkerId == null) {
                selectedWorkerId = "unknown";
            }
            DeliveryCommand command = toCommand(task, binding, adapterId, selectedWorkerId, null, null);
            handleDeliveryFailure(command, DispatchOutcome.unavailable(command, detail), detail);
        }
    }

    private static void put(Map<String, String> values, String key, String value) {
        String normalized = normalize(value);
        if (normalized != null) {
            values.put(key, normalized);
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
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
