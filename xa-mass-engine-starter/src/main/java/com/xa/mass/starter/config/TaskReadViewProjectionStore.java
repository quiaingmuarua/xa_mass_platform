package com.xa.mass.starter.config;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskHoldReason;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.model.TaskDefinitionPatch;
import com.xa.mass.engine.runtime.scheduling.TaskPolicyPresetDefinition;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class TaskReadViewProjectionStore {

    private final ConcurrentMap<String, TaskReadViewRecord> records = new ConcurrentHashMap<>();

    Optional<TaskReadViewRecord> get(String taskId) {
        return Optional.ofNullable(records.get(taskId));
    }

    List<TaskReadViewRecord> list(int offset, int limit) {
        int normalizedOffset = Math.max(0, offset);
        int normalizedLimit = Math.max(1, limit);
        return records.values().stream()
                .sorted(Comparator
                        .comparing(TaskReadViewRecord::updateTime,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TaskReadViewRecord::taskId))
                .skip(normalizedOffset)
                .limit(normalizedLimit)
                .toList();
    }

    List<TaskReadViewRecord> listByStatus(TaskStatus status, int limit) {
        int normalizedLimit = Math.max(1, limit);
        return records.values().stream()
                .filter(record -> status == null || record.status() == status)
                .sorted(Comparator
                        .comparing(TaskReadViewRecord::updateTime,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TaskReadViewRecord::taskId))
                .limit(normalizedLimit)
                .toList();
    }

    void recordTaskSnapshot(Task task) {
        if (task == null || task.getTid() == null || task.getTid().isBlank()) {
            return;
        }
        records.put(task.getTid(), TaskReadViewRecord.fromTask(task));
    }

    void recordCreated(String taskId, TaskShellCreateRequestDto request) {
        if (taskId == null || taskId.isBlank() || request == null) {
            return;
        }
        TaskReadViewRecord created = TaskReadViewRecord.fromCreateRequest(taskId.trim(), request);
        records.merge(taskId.trim(), created, TaskReadViewRecord::preferExistingWithCreateFallback);
    }

    void recordPatched(String taskId, TaskDefinitionPatch patch) {
        if (taskId == null || taskId.isBlank() || patch == null) {
            return;
        }
        records.compute(taskId.trim(), (id, existing) -> (existing == null ? TaskReadViewRecord.minimal(id) : existing)
                .withPatch(patch));
    }

    void recordStatus(String taskId, TaskStatus status, TaskHoldReason holdReason) {
        if (taskId == null || taskId.isBlank() || status == null) {
            return;
        }
        records.computeIfPresent(taskId.trim(), (id, existing) -> existing.withStatus(status, holdReason));
    }

    void recordTerminal(String taskId, TaskTerminalReason reason) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        records.computeIfPresent(taskId.trim(), (id, existing) -> existing.withTerminal(reason));
    }

    void recordIntake(String taskId, TaskIntakeStatus intakeStatus) {
        if (taskId == null || taskId.isBlank() || intakeStatus == null) {
            return;
        }
        records.computeIfPresent(taskId.trim(), (id, existing) -> existing.withIntake(intakeStatus));
    }

    void recordAppend(String taskId, int acceptedCount) {
        if (taskId == null || taskId.isBlank() || acceptedCount <= 0) {
            return;
        }
        records.computeIfPresent(taskId.trim(), (id, existing) -> existing.withAppendedCount(acceptedCount));
    }

    void remove(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        records.remove(taskId.trim());
    }

    record TaskReadViewRecord(
            String taskId,
            String tenantId,
            String taskName,
            TaskContract contract,
            String project,
            TaskStatus status,
            int taskTargetNumber,
            int taskEligibleNumber,
            int taskSuccessNumber,
            int taskNonSuccessNumber,
            int minRequiredWorkerCount,
            int peakAssignedWorkerCount,
            Map<String, Object> sharedConfig,
            TaskHoldReason holdReason,
            TaskExecutionSpec executionSpec,
            String sourceRef,
            TaskIntakeStatus intakeStatus,
            String userId,
            LocalDateTime createTime,
            LocalDateTime updateTime,
            LocalDateTime startTime,
            LocalDateTime endTime,
            TaskTerminalReason terminalReason
    ) {

        TaskReadViewRecord {
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("taskId is required");
            }
            taskId = taskId.trim();
            contract = contract == null ? TaskContract.BATCH : contract;
            status = status == null ? TaskStatus.NEW : status;
            sharedConfig = copyMap(sharedConfig);
            executionSpec = TaskExecutionSpec.normalized(executionSpec);
            intakeStatus = intakeStatus == null ? TaskIntakeStatus.SEALED : intakeStatus;
        }

        static TaskReadViewRecord minimal(String taskId) {
            LocalDateTime now = LocalDateTime.now();
            return new TaskReadViewRecord(
                    taskId,
                    null,
                    taskId,
                    TaskContract.BATCH,
                    null,
                    TaskStatus.NEW,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    Map.of(),
                    null,
                    new TaskExecutionSpec(),
                    null,
                    TaskIntakeStatus.SEALED,
                    null,
                    now,
                    now,
                    null,
                    null,
                    null);
        }

        static TaskReadViewRecord fromTask(Task task) {
            return new TaskReadViewRecord(
                    task.getTid(),
                    task.getTenantId(),
                    task.getTaskName(),
                    task.getContract(),
                    task.getProject(),
                    task.getStatus(),
                    task.getTaskTargetNumber(),
                    task.getTaskEligibleNumber(),
                    task.getTaskSuccessNumber(),
                    task.getTaskNonSuccessNumber(),
                    task.getMinRequiredWorkerCount(),
                    task.getPeakAssignedWorkerCount(),
                    task.getSharedConfig(),
                    task.getHoldReason(),
                    task.getExecutionSpec(),
                    task.getSourceRef(),
                    task.getIntakeStatus(),
                    task.getUser() == null ? null : task.getUser().getUserId(),
                    task.getCreateTime(),
                    task.getUpdateTime(),
                    task.getStartTime(),
                    task.getEndTime(),
                    task.getTerminalReason());
        }

        static TaskReadViewRecord fromCreateRequest(String taskId, TaskShellCreateRequestDto request) {
            LocalDateTime now = LocalDateTime.now();
            return new TaskReadViewRecord(
                    taskId,
                    request.getTenantId(),
                    deriveTaskName(request, taskId),
                    request.getContract(),
                    request.getProject(),
                    TaskStatus.NEW,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    request.getSharedConfig(),
                    null,
                    request.getExecutionSpec(),
                    normalizeSourceRef(request.getSourceRef()),
                    TaskIntakeStatus.OPEN,
                    request.getUserId(),
                    now,
                    now,
                    null,
                    null,
                    null);
        }

        TaskReadViewRecord preferExistingWithCreateFallback(TaskReadViewRecord fallback) {
            return new TaskReadViewRecord(
                    taskId,
                    tenantId != null ? tenantId : fallback.tenantId,
                    taskName != null ? taskName : fallback.taskName,
                    contract != null ? contract : fallback.contract,
                    project != null ? project : fallback.project,
                    status != null ? status : fallback.status,
                    taskTargetNumber,
                    taskEligibleNumber,
                    taskSuccessNumber,
                    taskNonSuccessNumber,
                    minRequiredWorkerCount,
                    peakAssignedWorkerCount,
                    sharedConfig.isEmpty() ? fallback.sharedConfig : sharedConfig,
                    holdReason,
                    executionSpec != null ? executionSpec : fallback.executionSpec,
                    sourceRef != null ? sourceRef : fallback.sourceRef,
                    intakeStatus != null ? intakeStatus : fallback.intakeStatus,
                    userId != null ? userId : fallback.userId,
                    createTime != null ? createTime : fallback.createTime,
                    updateTime != null ? updateTime : fallback.updateTime,
                    startTime,
                    endTime,
                    terminalReason);
        }

        TaskReadViewRecord withPatch(TaskDefinitionPatch patch) {
            return new TaskReadViewRecord(
                    taskId,
                    tenantId,
                    taskName,
                    contract,
                    patch.project() != null ? patch.project() : project,
                    status,
                    taskTargetNumber,
                    taskEligibleNumber,
                    taskSuccessNumber,
                    taskNonSuccessNumber,
                    minRequiredWorkerCount,
                    peakAssignedWorkerCount,
                    patch.sharedConfig() != null ? patch.sharedConfig() : sharedConfig,
                    holdReason,
                    executionSpec,
                    sourceRef,
                    intakeStatus,
                    patch.userId() != null ? patch.userId() : userId,
                    createTime,
                    LocalDateTime.now(),
                    startTime,
                    endTime,
                    terminalReason);
        }

        TaskReadViewRecord withStatus(TaskStatus nextStatus, TaskHoldReason nextHoldReason) {
            LocalDateTime now = LocalDateTime.now();
            return new TaskReadViewRecord(
                    taskId,
                    tenantId,
                    taskName,
                    contract,
                    project,
                    nextStatus,
                    taskTargetNumber,
                    taskEligibleNumber,
                    taskSuccessNumber,
                    taskNonSuccessNumber,
                    minRequiredWorkerCount,
                    peakAssignedWorkerCount,
                    sharedConfig,
                    nextHoldReason,
                    executionSpec,
                    sourceRef,
                    intakeStatus,
                    userId,
                    createTime,
                    now,
                    nextStatus == TaskStatus.RUNNING && startTime == null ? now : startTime,
                    nextStatus != null && nextStatus.isFinal() && endTime == null ? now : endTime,
                    nextStatus != null && nextStatus.isFinal() ? terminalReason : null);
        }

        TaskReadViewRecord withTerminal(TaskTerminalReason reason) {
            LocalDateTime now = LocalDateTime.now();
            return new TaskReadViewRecord(
                    taskId,
                    tenantId,
                    taskName,
                    contract,
                    project,
                    TaskStatus.TERMINAL,
                    taskTargetNumber,
                    taskEligibleNumber,
                    taskSuccessNumber,
                    taskNonSuccessNumber,
                    minRequiredWorkerCount,
                    peakAssignedWorkerCount,
                    sharedConfig,
                    null,
                    executionSpec,
                    sourceRef,
                    TaskIntakeStatus.SEALED,
                    userId,
                    createTime,
                    now,
                    startTime,
                    endTime == null ? now : endTime,
                    reason);
        }

        TaskReadViewRecord withIntake(TaskIntakeStatus nextIntakeStatus) {
            return new TaskReadViewRecord(
                    taskId,
                    tenantId,
                    taskName,
                    contract,
                    project,
                    status,
                    taskTargetNumber,
                    taskEligibleNumber,
                    taskSuccessNumber,
                    taskNonSuccessNumber,
                    minRequiredWorkerCount,
                    peakAssignedWorkerCount,
                    sharedConfig,
                    holdReason,
                    executionSpec,
                    sourceRef,
                    nextIntakeStatus,
                    userId,
                    createTime,
                    LocalDateTime.now(),
                    startTime,
                    endTime,
                    terminalReason);
        }

        TaskReadViewRecord withAppendedCount(int acceptedCount) {
            return new TaskReadViewRecord(
                    taskId,
                    tenantId,
                    taskName,
                    contract,
                    project,
                    status,
                    safeAdd(taskTargetNumber, acceptedCount),
                    safeAdd(taskEligibleNumber, acceptedCount),
                    taskSuccessNumber,
                    safeAdd(taskNonSuccessNumber, acceptedCount),
                    minRequiredWorkerCount,
                    peakAssignedWorkerCount,
                    sharedConfig,
                    holdReason,
                    executionSpec,
                    sourceRef,
                    intakeStatus,
                    userId,
                    createTime,
                    LocalDateTime.now(),
                    startTime,
                    endTime,
                    terminalReason);
        }

        private static String deriveTaskName(TaskShellCreateRequestDto request, String taskId) {
            String project = request.getProject() != null ? request.getProject().trim() : "task";
            TaskContract contract = TaskPolicyPresetDefinition.defaultContract(request.getContract());
            String normalizedContract = contract.name().toLowerCase(Locale.ROOT);
            String profile = request.getExecutionSpec() != null && request.getExecutionSpec().getProfile() != null
                    ? request.getExecutionSpec().getProfile().name().toLowerCase(Locale.ROOT)
                    : "standard";
            String sourceRef = normalizeSourceRef(request.getSourceRef());
            String sourceHint = sourceRef == null ? null : basename(sourceRef);
            String shortTaskId = taskId.length() <= 8 ? taskId : taskId.substring(0, 8);
            if (sourceHint != null && !sourceHint.isBlank()) {
                return project + "-" + normalizedContract + "-" + profile + "-" + sourceHint + "-" + shortTaskId;
            }
            return project + "-" + normalizedContract + "-" + profile + "-" + shortTaskId;
        }

        private static String basename(String value) {
            String normalized = value.replace('\\', '/');
            int slash = normalized.lastIndexOf('/');
            String leaf = slash >= 0 ? normalized.substring(slash + 1) : normalized;
            String sanitized = leaf.replaceAll("[^A-Za-z0-9._-]", "-");
            return sanitized.isBlank() ? null : sanitized;
        }

        private static String normalizeSourceRef(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return value.trim();
        }

        private static int safeAdd(int left, int right) {
            long value = (long) left + right;
            return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
        }

        private static Map<String, Object> copyMap(Map<String, Object> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                if (entry.getKey() == null) {
                    throw new NullPointerException("map key");
                }
                copy.put(entry.getKey(), entry.getValue());
            }
            return java.util.Collections.unmodifiableMap(copy);
        }
    }
}
