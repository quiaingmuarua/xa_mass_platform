package com.xa.mass.api.internal;

import com.xa.mass.api.model.task.TaskApiContracts.ApiTask;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskAppendOutcome;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskCommandOutcome;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskCounters;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskCreateOutcome;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskExecution;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskGetResult;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskListResult;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskResultArchive;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskResultItem;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskResultWindow;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskShell;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskSyncAppendOutcome;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskTimestamps;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskUpdateOutcome;
import com.xa.mass.sdk.authz.TaskOwnershipStamp;
import com.xa.mass.sdk.model.TaskCommandResult;
import com.xa.mass.sdk.model.TaskDetailSnapshot;
import com.xa.mass.sdk.model.TaskExecutionOptions;
import com.xa.mass.sdk.model.TaskResultItemSnapshot;
import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.model.TaskStateSnapshot;
import com.xa.mass.sdk.model.TaskSummarySnapshot;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

final class TaskApiContractAssembler {

    private static final Map<String, String> TASK_FIELD_SOURCES = Map.ofEntries(
            Map.entry("id", "compatibilityAlias"),
            Map.entry("taskId", "controlPlaneShell"),
            Map.entry("tid", "compatibilityAlias"),
            Map.entry("taskName", "controlPlaneShell"),
            Map.entry("tenantId", "controlPlaneShell"),
            Map.entry("project", "controlPlaneShell"),
            Map.entry("userId", "controlPlaneShell"),
            Map.entry("contract", "controlPlaneShell"),
            Map.entry("status", "runtimeCurrent"),
            Map.entry("intakeStatus", "runtimeCurrent"),
            Map.entry("terminalReason", "runtimeCurrent"),
            Map.entry("holdReason", "runtimeCurrent"),
            Map.entry("sourceRef", "controlPlaneShell"),
            Map.entry("sharedConfig", "controlPlaneShell"),
            Map.entry("execution", "executionPolicy"),
            Map.entry("executionSpec", "compatibilityAlias"),
            Map.entry("counters", "runtimeCurrent"),
            Map.entry("timestamps", "lifecycleTimestamp"),
            Map.entry("taskTargetNumber", "compatibilityAlias"),
            Map.entry("taskEligibleNumber", "compatibilityAlias"),
            Map.entry("taskSuccessNumber", "compatibilityAlias"),
            Map.entry("taskNonSuccessNumber", "compatibilityAlias"),
            Map.entry("minRequiredWorkerCount", "compatibilityAlias"),
            Map.entry("peakAssignedWorkerCount", "compatibilityAlias"),
            Map.entry("successCount", "compatibilityAlias"),
            Map.entry("eligibleCount", "compatibilityAlias"),
            Map.entry("executionProfile", "compatibilityAlias"),
            Map.entry("workloadClass", "compatibilityAlias"),
            Map.entry("batchSize", "compatibilityAlias"),
            Map.entry("maxRuntimeSeconds", "compatibilityAlias"),
            Map.entry("defaultMaxRetryCount", "compatibilityAlias"),
            Map.entry("createTime", "compatibilityAlias"),
            Map.entry("updateTime", "compatibilityAlias"),
            Map.entry("startTime", "compatibilityAlias"),
            Map.entry("endTime", "compatibilityAlias"),
            Map.entry("updatedAt", "compatibilityAlias")
    );
    private static final Map<String, String> TASK_SHELL_FIELD_SOURCES = Map.ofEntries(
            Map.entry("id", "compatibilityAlias"),
            Map.entry("taskId", "controlPlaneShell"),
            Map.entry("tid", "compatibilityAlias"),
            Map.entry("taskName", "controlPlaneShell"),
            Map.entry("tenantId", "controlPlaneShell"),
            Map.entry("project", "controlPlaneShell"),
            Map.entry("userId", "controlPlaneShell"),
            Map.entry("contract", "controlPlaneShell"),
            Map.entry("sourceRef", "controlPlaneShell"),
            Map.entry("sharedConfig", "controlPlaneShell"),
            Map.entry("execution", "executionPolicy"),
            Map.entry("executionSpec", "compatibilityAlias")
    );

    private final DateTimeFormatter dateTimeFormatter;

    TaskApiContractAssembler(DateTimeFormatter dateTimeFormatter) {
        this.dateTimeFormatter = dateTimeFormatter;
    }

    ApiTaskListResult toTaskListResult(List<ApiTask> items) {
        List<ApiTask> safeItems = items == null ? List.of() : List.copyOf(items);
        return new ApiTaskListResult(safeItems, safeItems.size());
    }

    ApiTaskCreateOutcome toCreateOutcome(TaskShellSnapshot task,
                                         TaskExecutionOptions executionOptions,
                                         String principalId,
                                         String message) {
        ApiTaskShell apiTask = toApiTaskShell(task, executionOptions);
        return new ApiTaskCreateOutcome(
                apiTask,
                task != null ? task.getTaskId() : null,
                task != null ? task.getTaskName() : null,
                task != null ? task.getProject() : null,
                task != null ? task.getUserId() : null,
                principalId,
                task != null ? task.getContract() : null,
                null,
                message
        );
    }

    ApiTaskGetResult toGetResult(TaskDetailSnapshot task, Map<String, Object> security) {
        return new ApiTaskGetResult(toApiTask(task), security == null ? Map.of() : Map.copyOf(security));
    }

    ApiTaskUpdateOutcome toUpdateOutcome(String taskId, TaskStateSnapshot state, String message) {
        return new ApiTaskUpdateOutcome(
                taskId,
                state != null ? state.getStatus() : null,
                state != null ? state.getIntakeStatus() : null,
                message
        );
    }

    ApiTaskAppendOutcome toAppendOutcome(String taskId, int added, String status, String intakeStatus, String message) {
        return new ApiTaskAppendOutcome(taskId, added, status, intakeStatus, message);
    }

    ApiTaskSyncAppendOutcome toSyncAppendOutcome(String taskId,
                                                 String messageId,
                                                 long timeoutMs,
                                                 TaskWorkFinalSnapshot finalSnapshot) {
        boolean synced = finalSnapshot != null;
        return new ApiTaskSyncAppendOutcome(
                taskId,
                messageId,
                synced,
                !synced,
                timeoutMs,
                synced ? finalSnapshot.status() : null,
                synced ? finalSnapshot.finalReason() : null,
                synced ? finalSnapshot.output() : null,
                synced ? finalSnapshot.errorCode() : null,
                synced ? finalSnapshot.errorMessage() : null
        );
    }

    ApiTaskCommandOutcome toCommandOutcome(TaskCommandResult result) {
        return new ApiTaskCommandOutcome(
                result.getTaskId(),
                result.getCommand(),
                result.isAccepted(),
                result.getStatus(),
                result.getIntakeStatus(),
                result.getTerminalReason(),
                result.getHoldReason(),
                result.getFailureReason(),
                result.getReasonCode()
        );
    }

    ApiTaskResultWindow toResultWindow(String taskId,
                                       boolean taskTerminal,
                                       boolean archiveReady,
                                       List<ApiTaskResultItem> items,
                                       long nextAfterSeq,
                                       boolean hasMore,
                                       String archiveUrl) {
        return new ApiTaskResultWindow(
                taskTerminal && archiveReady ? "ARCHIVE_READY" : "LIVE",
                taskId,
                taskTerminal,
                archiveReady,
                items == null ? List.of() : List.copyOf(items),
                nextAfterSeq,
                hasMore,
                archiveUrl
        );
    }

    ApiTaskResultArchive toResultArchive(String taskId,
                                         boolean ready,
                                         String contentType,
                                         long itemCount,
                                         Long byteSize,
                                         String checksum,
                                         String downloadUrl) {
        return new ApiTaskResultArchive(
                ready,
                taskId,
                "ndjson",
                contentType,
                "gzip",
                itemCount,
                byteSize,
                checksum,
                downloadUrl == null ? "" : downloadUrl
        );
    }

    ApiTask toApiTask(TaskSummarySnapshot task) {
        if (task == null) {
            return null;
        }
        ApiTaskExecution execution = toExecution(task.getExecutionSpec());
        ApiTaskCounters counters = new ApiTaskCounters(
                0,
                task.getTaskEligibleNumber(),
                task.getTaskSuccessNumber(),
                0,
                0,
                0
        );
        String updatedAt = formatDateTime(task.getUpdateTime());
        ApiTaskTimestamps timestamps = new ApiTaskTimestamps(null, updatedAt, null, null);
        return new ApiTask(
                task.getTaskId(),
                task.getTaskId(),
                task.getTaskId(),
                task.getTaskName(),
                task.getTenantId(),
                task.getProject(),
                task.getUserId(),
                task.getContract(),
                task.getStatus(),
                null,
                task.getTerminalReason(),
                null,
                null,
                null,
                execution,
                execution,
                counters,
                timestamps,
                0,
                task.getTaskEligibleNumber(),
                task.getTaskSuccessNumber(),
                0,
                0,
                0,
                task.getTaskSuccessNumber(),
                task.getTaskEligibleNumber(),
                execution.profile(),
                execution.workloadClass(),
                execution.batchSize(),
                execution.maxRuntimeSeconds(),
                execution.defaultMaxRetryCount(),
                null,
                updatedAt,
                null,
                null,
                updatedAt,
                TASK_FIELD_SOURCES
        );
    }

    ApiTask toApiTask(TaskDetailSnapshot task) {
        if (task == null) {
            return null;
        }
        ApiTaskExecution execution = toExecution(task.getExecutionSpec());
        ApiTaskCounters counters = new ApiTaskCounters(
                task.getTaskTargetNumber(),
                task.getTaskEligibleNumber(),
                task.getTaskSuccessNumber(),
                task.getTaskNonSuccessNumber(),
                task.getMinRequiredWorkerCount(),
                task.getPeakAssignedWorkerCount()
        );
        String createdAt = formatDateTime(task.getCreateTime());
        String updatedAt = formatDateTime(task.getUpdateTime());
        String startedAt = formatDateTime(task.getStartTime());
        String endedAt = formatDateTime(task.getEndTime());
        ApiTaskTimestamps timestamps = new ApiTaskTimestamps(createdAt, updatedAt, startedAt, endedAt);
        return new ApiTask(
                task.getTaskId(),
                task.getTaskId(),
                task.getTaskId(),
                task.getTaskName(),
                task.getTenantId(),
                task.getProject(),
                task.getUserId(),
                task.getContract(),
                task.getStatus(),
                task.getIntakeStatus(),
                task.getTerminalReason(),
                task.getHoldReason(),
                task.getSourceRef(),
                sanitizeSharedConfig(task.getSharedConfig()),
                execution,
                execution,
                counters,
                timestamps,
                task.getTaskTargetNumber(),
                task.getTaskEligibleNumber(),
                task.getTaskSuccessNumber(),
                task.getTaskNonSuccessNumber(),
                task.getMinRequiredWorkerCount(),
                task.getPeakAssignedWorkerCount(),
                task.getTaskSuccessNumber(),
                task.getTaskEligibleNumber(),
                execution.profile(),
                execution.workloadClass(),
                execution.batchSize(),
                execution.maxRuntimeSeconds(),
                execution.defaultMaxRetryCount(),
                createdAt,
                updatedAt,
                startedAt,
                endedAt,
                updatedAt,
                TASK_FIELD_SOURCES
        );
    }

    ApiTaskShell toApiTaskShell(TaskShellSnapshot task, TaskExecutionOptions executionOptions) {
        if (task == null) {
            return null;
        }
        ApiTaskExecution execution = toExecution(executionOptions);
        return new ApiTaskShell(
                task.getTaskId(),
                task.getTaskId(),
                task.getTaskId(),
                task.getTaskName(),
                task.getTenantId(),
                task.getProject(),
                task.getUserId(),
                task.getContract(),
                task.getSourceRef(),
                null,
                execution,
                execution,
                TASK_SHELL_FIELD_SOURCES
        );
    }

    ApiTaskResultItem toResultItem(TaskResultItemSnapshot row) {
        return new ApiTaskResultItem(
                row.getSeq(),
                row.getMessageId(),
                row.getEventCode(),
                row.getStatus(),
                row.getFinalReason(),
                row.getRetryCount(),
                row.getMaxRetryCount(),
                row.getWorkerId(),
                row.getBatchId(),
                row.getAttemptId(),
                row.getPayloadRef(),
                formatInstant(row.getCreateTime()),
                formatInstant(row.getAssignedTime()),
                formatInstant(row.getStartTime()),
                formatInstant(row.getCompleteTime()),
                formatInstant(row.getUpdateTime()),
                row.getErrorCode(),
                row.getErrorMessage(),
                row.getOutput()
        );
    }

    private ApiTaskExecution toExecution(TaskExecutionOptions options) {
        TaskExecutionOptions normalized = TaskExecutionOptions.normalized(options);
        return new ApiTaskExecution(
                normalized.getProfile(),
                normalized.getWorkloadClass(),
                normalized.getBatchSize(),
                normalized.getMaxRuntimeSeconds(),
                normalized.getDefaultMaxRetryCount(),
                normalized.isForeground()
        );
    }

    private Map<String, Object> sanitizeSharedConfig(Map<String, Object> sharedConfig) {
        if (sharedConfig == null || sharedConfig.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new java.util.LinkedHashMap<>(sharedConfig);
        sanitized.remove(TaskOwnershipStamp.SHARED_CONFIG_KEY);
        return Map.copyOf(sanitized);
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? "" : value.format(dateTimeFormatter);
    }

    private String formatInstant(Instant value) {
        return value == null ? "" : LocalDateTime.ofInstant(value, ZoneId.systemDefault()).format(dateTimeFormatter);
    }
}
