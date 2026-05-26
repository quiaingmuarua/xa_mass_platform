package com.xa.mass.engine.command;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

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
    private final ConcurrentSkipListMap<Long, Set<String>> commandIdsByDeadline = new ConcurrentSkipListMap<>();
    private final ConcurrentHashMap<String, Set<String>> commandIdsByWorkerId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WorkerCommandStatus, Set<String>> commandIdsByStatus = new ConcurrentHashMap<>();
    private final Set<String> deliveryAttemptInFlightCommandIds = ConcurrentHashMap.newKeySet();

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
        if (!WorkerCommandCatalog.isApproved(request.commandType())) {
            return result(WorkerCommandLifecycleResultCode.REJECTED, null, null, null,
                    "unsupported worker command type: " + request.commandType());
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
        indexWorker(created);
        indexDeadline(created);
        indexStatus(created);
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
        updateStatusIndex(current, updated);
        if (isTerminal(targetStatus)) {
            removeDeadlineIndex(updated);
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

    public WorkerCommandRecord beginDeliveryAttempt(String commandId, String reason) {
        String normalizedCommandId = normalizeNullable(commandId);
        if (normalizedCommandId == null) {
            throw new IllegalArgumentException("commandId must not be blank");
        }
        if (!deliveryAttemptInFlightCommandIds.add(normalizedCommandId)) {
            return null;
        }
        WorkerCommandRecord attemptRecord = recordDeliveryAttempt(normalizedCommandId, reason);
        if (attemptRecord == null || attemptRecord.status() != WorkerCommandStatus.REQUESTED) {
            completeDeliveryAttempt(normalizedCommandId);
        }
        return attemptRecord;
    }

    public void completeDeliveryAttempt(String commandId) {
        String normalizedCommandId = normalizeNullable(commandId);
        if (normalizedCommandId != null) {
            deliveryAttemptInFlightCommandIds.remove(normalizedCommandId);
        }
    }

    private WorkerCommandRecord recordDeliveryAttempt(String commandId, String reason) {
        String normalizedCommandId = normalizeNullable(commandId);
        if (normalizedCommandId == null) {
            throw new IllegalArgumentException("commandId must not be blank");
        }
        WorkerCommandRecord current = recordsByCommandId.get(normalizedCommandId);
        if (current == null) {
            return null;
        }
        WorkerCommandRecord updated = current.withDeliveryAttempt(normalizeNullable(reason), Instant.now(clock));
        boolean replaced = recordsByCommandId.replace(normalizedCommandId, current, updated);
        return replaced ? updated : recordDeliveryAttempt(normalizedCommandId, reason);
    }

    public WorkerCommandRecord recordStatusReason(String commandId, String reason) {
        String normalizedCommandId = normalizeNullable(commandId);
        if (normalizedCommandId == null) {
            throw new IllegalArgumentException("commandId must not be blank");
        }
        WorkerCommandRecord current = recordsByCommandId.get(normalizedCommandId);
        if (current == null) {
            return null;
        }
        WorkerCommandRecord updated = current.withStatusReason(normalizeNullable(reason), Instant.now(clock));
        boolean replaced = recordsByCommandId.replace(normalizedCommandId, current, updated);
        return replaced ? updated : recordStatusReason(normalizedCommandId, reason);
    }

    public List<WorkerCommandRecord> commandsForWorker(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return List.of();
        }
        Set<String> commandIds = commandIdsByWorkerId.get(normalizedWorkerId);
        if (commandIds == null || commandIds.isEmpty()) {
            return List.of();
        }
        return commandIds.stream()
                .map(recordsByCommandId::get)
                .filter(Objects::nonNull)
                .sorted(java.util.Comparator.comparing(WorkerCommandRecord::createdAt))
                .toList();
    }

    public List<WorkerCommandRecord> commandsByStatus(WorkerCommandStatus status, int limit) {
        if (status == null || limit <= 0) {
            return List.of();
        }
        Set<String> commandIds = commandIdsByStatus.get(status);
        if (commandIds == null || commandIds.isEmpty()) {
            return List.of();
        }
        ArrayList<WorkerCommandRecord> results = new ArrayList<>();
        for (String commandId : commandIds) {
            if (results.size() >= limit) {
                break;
            }
            WorkerCommandRecord record = recordsByCommandId.get(commandId);
            if (record == null || record.status() != status) {
                commandIds.remove(commandId);
                continue;
            }
            results.add(record);
        }
        return List.copyOf(results);
    }

    public List<WorkerCommandLifecycleResult> claimPendingCommandsForWorker(String workerId,
                                                                            int limit,
                                                                            String reason) {
        if (limit <= 0) {
            return List.of();
        }
        ArrayList<WorkerCommandLifecycleResult> results = new ArrayList<>();
        for (WorkerCommandRecord record : commandsForWorker(workerId)) {
            if (results.size() >= limit) {
                break;
            }
            if (record.status() != WorkerCommandStatus.REQUESTED) {
                continue;
            }
            WorkerCommandLifecycleResult result = markDeliveryAccepted(record.commandId(), reason);
            if (result.success()) {
                results.add(result);
            }
        }
        return List.copyOf(results);
    }

    public List<WorkerCommandLifecycleResult> expireDueCommands(Instant now, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        long nowEpochMillis = (now == null ? Instant.now(clock) : now).toEpochMilli();
        ArrayList<WorkerCommandLifecycleResult> results = new ArrayList<>();
        NavigableMap<Long, Set<String>> dueDeadlines = commandIdsByDeadline.headMap(nowEpochMillis, true);
        for (Map.Entry<Long, Set<String>> entry : new ArrayList<>(dueDeadlines.entrySet())) {
            Long deadlineEpochMillis = entry.getKey();
            Set<String> commandIds = entry.getValue();
            for (String commandId : new ArrayList<>(commandIds)) {
                if (results.size() >= limit) {
                    return List.copyOf(results);
                }
                WorkerCommandRecord record = recordsByCommandId.get(commandId);
                if (record == null
                        || !Objects.equals(record.deadlineEpochMillis(), deadlineEpochMillis)
                        || !isExpirable(record.status())) {
                    commandIds.remove(commandId);
                    removeEmptyDeadlineBucket(deadlineEpochMillis, commandIds);
                    continue;
                }
                WorkerCommandLifecycleResult result = markExpired(commandId, "worker command deadline expired");
                if (result.success()) {
                    results.add(result);
                }
                commandIds.remove(commandId);
                removeEmptyDeadlineBucket(deadlineEpochMillis, commandIds);
            }
        }
        return List.copyOf(results);
    }

    private static boolean canTransition(WorkerCommandStatus currentStatus, WorkerCommandStatus targetStatus) {
        if (isTerminal(currentStatus)) {
            return false;
        }
        return ALLOWED_TRANSITIONS.getOrDefault(currentStatus, List.of()).contains(targetStatus);
    }

    private void indexDeadline(WorkerCommandRecord record) {
        if (record == null || record.deadlineEpochMillis() == null) {
            return;
        }
        commandIdsByDeadline
                .computeIfAbsent(record.deadlineEpochMillis(), ignored -> ConcurrentHashMap.newKeySet())
                .add(record.commandId());
    }

    private void indexWorker(WorkerCommandRecord record) {
        if (record == null || record.workerId() == null) {
            return;
        }
        commandIdsByWorkerId
                .computeIfAbsent(record.workerId(), ignored -> ConcurrentHashMap.newKeySet())
                .add(record.commandId());
    }

    private void indexStatus(WorkerCommandRecord record) {
        if (record == null || record.status() == null) {
            return;
        }
        commandIdsByStatus
                .computeIfAbsent(record.status(), ignored -> ConcurrentHashMap.newKeySet())
                .add(record.commandId());
    }

    private void updateStatusIndex(WorkerCommandRecord previous, WorkerCommandRecord updated) {
        if (previous != null && previous.status() != null) {
            Set<String> previousIds = commandIdsByStatus.get(previous.status());
            if (previousIds != null) {
                previousIds.remove(previous.commandId());
                if (previousIds.isEmpty()) {
                    commandIdsByStatus.remove(previous.status(), previousIds);
                }
            }
        }
        indexStatus(updated);
    }

    private void removeDeadlineIndex(WorkerCommandRecord record) {
        if (record == null || record.deadlineEpochMillis() == null) {
            return;
        }
        Set<String> commandIds = commandIdsByDeadline.get(record.deadlineEpochMillis());
        if (commandIds == null) {
            return;
        }
        commandIds.remove(record.commandId());
        removeEmptyDeadlineBucket(record.deadlineEpochMillis(), commandIds);
    }

    private void removeEmptyDeadlineBucket(Long deadlineEpochMillis, Set<String> commandIds) {
        if (deadlineEpochMillis != null && commandIds != null && commandIds.isEmpty()) {
            commandIdsByDeadline.remove(deadlineEpochMillis, commandIds);
        }
    }

    private static boolean isExpirable(WorkerCommandStatus status) {
        return status == WorkerCommandStatus.REQUESTED
                || status == WorkerCommandStatus.DELIVERY_ACCEPTED
                || status == WorkerCommandStatus.EXECUTION_ACCEPTED;
    }

    private static boolean isTerminal(WorkerCommandStatus status) {
        return status == WorkerCommandStatus.SUCCEEDED
                || status == WorkerCommandStatus.FAILED
                || status == WorkerCommandStatus.EXPIRED;
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
