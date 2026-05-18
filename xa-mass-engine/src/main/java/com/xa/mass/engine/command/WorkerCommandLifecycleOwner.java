package com.xa.mass.engine.command;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns worker command lifecycle truth.
 *
 * <p>This owner is deliberately independent from task result convergence and
 * task-work dispatch. Delivery handoff and acknowledgement/status ingress must
 * enter through this owner.</p>
 */
public final class WorkerCommandLifecycleOwner {

    private static final Map<WorkerCommandStatus, List<WorkerCommandStatus>> ALLOWED_TRANSITIONS = Map.of(
            WorkerCommandStatus.REQUESTED, List.of(
                    WorkerCommandStatus.DELIVERY_ACCEPTED,
                    WorkerCommandStatus.EXPIRED,
                    WorkerCommandStatus.FAILED
            ),
            WorkerCommandStatus.DELIVERY_ACCEPTED, List.of(
                    WorkerCommandStatus.EXECUTION_ACCEPTED,
                    WorkerCommandStatus.SUCCEEDED,
                    WorkerCommandStatus.FAILED,
                    WorkerCommandStatus.EXPIRED
            ),
            WorkerCommandStatus.EXECUTION_ACCEPTED, List.of(
                    WorkerCommandStatus.SUCCEEDED,
                    WorkerCommandStatus.FAILED,
                    WorkerCommandStatus.EXPIRED
            )
    );

    private final Clock clock;
    private final ConcurrentHashMap<String, WorkerCommandRecord> recordsByCommandId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WorkerCommandRequest> requestsByCommandId = new ConcurrentHashMap<>();

    public WorkerCommandLifecycleOwner() {
        this(Clock.systemUTC());
    }

    WorkerCommandLifecycleOwner(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public WorkerCommandLifecycleResult requestCommand(WorkerCommandRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        WorkerCommandRecord existing = recordsByCommandId.get(request.commandId());
        if (existing != null) {
            WorkerCommandRequest existingRequest = requestsByCommandId.get(request.commandId());
            if (existingRequest != null && existingRequest.sameRequest(request)) {
                return result(WorkerCommandLifecycleResultCode.IDEMPOTENT, existing, existing.status(),
                        existing.status(), "command request already recorded");
            }
            return result(WorkerCommandLifecycleResultCode.CONFLICT, existing, existing.status(),
                    existing.status(), "commandId already belongs to a different request");
        }

        WorkerCommandRecord created = WorkerCommandRecord.requested(request, Instant.now(clock));
        WorkerCommandRecord raced = recordsByCommandId.putIfAbsent(request.commandId(), created);
        if (raced != null) {
            return result(WorkerCommandLifecycleResultCode.CONFLICT, raced, raced.status(),
                    raced.status(), "commandId already belongs to a different request");
        }
        requestsByCommandId.put(request.commandId(), request);
        return result(WorkerCommandLifecycleResultCode.ACCEPTED, created, null,
                WorkerCommandStatus.REQUESTED, "command request recorded");
    }

    public WorkerCommandLifecycleResult transition(String commandId,
                                                   WorkerCommandStatus targetStatus,
                                                   String reason) {
        String normalizedCommandId = normalizeNullable(commandId);
        if (normalizedCommandId == null) {
            throw new IllegalArgumentException("commandId must not be blank");
        }
        if (targetStatus == null) {
            throw new IllegalArgumentException("targetStatus must not be null");
        }

        WorkerCommandRecord current = recordsByCommandId.get(normalizedCommandId);
        if (current == null) {
            return result(WorkerCommandLifecycleResultCode.NOT_FOUND, null, null, null,
                    "command not found");
        }
        if (current.status() == targetStatus) {
            return result(WorkerCommandLifecycleResultCode.IDEMPOTENT, current, current.status(),
                    current.status(), "command status already applied");
        }
        if (!canTransition(current.status(), targetStatus)) {
            return result(WorkerCommandLifecycleResultCode.INVALID_TRANSITION, current, current.status(),
                    current.status(), "invalid command status transition");
        }

        WorkerCommandRecord updated = current.withStatus(targetStatus, normalizeNullable(reason), Instant.now(clock));
        boolean replaced = recordsByCommandId.replace(normalizedCommandId, current, updated);
        if (!replaced) {
            return transition(normalizedCommandId, targetStatus, reason);
        }
        return result(WorkerCommandLifecycleResultCode.ACCEPTED, updated, current.status(),
                targetStatus, "command status updated");
    }

    public WorkerCommandLifecycleResult applyAcknowledgement(WorkerCommandAcknowledgement acknowledgement) {
        if (acknowledgement == null) {
            throw new IllegalArgumentException("acknowledgement must not be null");
        }
        return transition(acknowledgement.commandId(), acknowledgement.targetStatus(), acknowledgement.reason());
    }

    public WorkerCommandLifecycleResult markDeliveryAccepted(String commandId, String reason) {
        return applyAcknowledgement(WorkerCommandAcknowledgement.deliveryAccepted(commandId, reason));
    }

    public WorkerCommandLifecycleResult markExecutionAccepted(String commandId, String reason) {
        return applyAcknowledgement(WorkerCommandAcknowledgement.executionAccepted(commandId, reason));
    }

    public WorkerCommandLifecycleResult markSucceeded(String commandId, String reason) {
        return applyAcknowledgement(WorkerCommandAcknowledgement.succeeded(commandId, reason));
    }

    public WorkerCommandLifecycleResult markFailed(String commandId, String reason) {
        return applyAcknowledgement(WorkerCommandAcknowledgement.failed(commandId, reason));
    }

    public WorkerCommandLifecycleResult markExpired(String commandId, String reason) {
        return applyAcknowledgement(WorkerCommandAcknowledgement.expired(commandId, reason));
    }

    public Optional<WorkerCommandRecord> command(String commandId) {
        String normalizedCommandId = normalizeNullable(commandId);
        return normalizedCommandId == null
                ? Optional.empty()
                : Optional.ofNullable(recordsByCommandId.get(normalizedCommandId));
    }

    public List<WorkerCommandRecord> commandsForWorker(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return List.of();
        }
        return recordsByCommandId.values().stream()
                .filter(record -> normalizedWorkerId.equals(record.workerId()))
                .sorted(java.util.Comparator.comparing(WorkerCommandRecord::createdAt))
                .toList();
    }

    private static boolean canTransition(WorkerCommandStatus currentStatus, WorkerCommandStatus targetStatus) {
        if (currentStatus == WorkerCommandStatus.SUCCEEDED
                || currentStatus == WorkerCommandStatus.FAILED
                || currentStatus == WorkerCommandStatus.EXPIRED) {
            return false;
        }
        return ALLOWED_TRANSITIONS.getOrDefault(currentStatus, List.of()).contains(targetStatus);
    }

    private static WorkerCommandLifecycleResult result(WorkerCommandLifecycleResultCode code,
                                                       WorkerCommandRecord record,
                                                       WorkerCommandStatus previousStatus,
                                                       WorkerCommandStatus currentStatus,
                                                       String reason) {
        return new WorkerCommandLifecycleResult(code, record, previousStatus, currentStatus, reason);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
