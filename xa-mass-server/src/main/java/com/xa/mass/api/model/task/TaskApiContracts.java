package com.xa.mass.api.model.task;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(name = "TaskApiContracts", description = "Container for public task API response objects")
public final class TaskApiContracts {

    private TaskApiContracts() {
    }

    @Schema(name = "ApiTask", description = "Public task object used by task list, detail, and create responses")
    public record ApiTask(
            @Schema(description = "Compatibility alias for taskId", example = "task-uuid")
            String id,
            @Schema(description = "Stable task identifier", example = "task-uuid")
            String taskId,
            @Schema(description = "Compatibility alias for taskId", example = "task-uuid")
            String tid,
            @Schema(description = "Server-derived display name", example = "demoApp-BATCH-task-uuid")
            String taskName,
            @Schema(description = "Tenant id. Current runtime is single-tenant with tenant-aware semantics.", example = "default")
            String tenantId,
            @Schema(description = "Project code that owns the task", example = "demoApp")
            String project,
            @Schema(description = "Task owner user id", example = "agent")
            String userId,
            @Schema(description = "Task runtime contract", allowableValues = {"SESSION", "BATCH"}, example = "BATCH")
            String contract,
            @Schema(description = "Task lifecycle status", example = "READY")
            String status,
            @Schema(description = "Task intake window status", allowableValues = {"OPEN", "SEALED"}, example = "OPEN")
            String intakeStatus,
            @Schema(description = "Terminal reason when status is TERMINAL", example = "ALL_MESSAGES_SUCCEEDED")
            String terminalReason,
            @Schema(description = "Hold reason when task is blocked", example = "MANUAL_BLOCKED")
            String holdReason,
            @Schema(description = "External source reference for the task shell", example = "import://demo/seed.ndjson")
            String sourceRef,
            @Schema(description = "Public task shared config with framework-owned security metadata stripped")
            Map<String, Object> sharedConfig,
            @Schema(description = "Canonical execution policy view")
            ApiTaskExecution execution,
            @Schema(description = "Compatibility alias for execution")
            ApiTaskExecution executionSpec,
            @Schema(description = "Canonical task aggregate counters")
            ApiTaskCounters counters,
            @Schema(description = "Canonical task lifecycle timestamps")
            ApiTaskTimestamps timestamps,
            @Schema(description = "Compatibility target count alias", example = "1000")
            int taskTargetNumber,
            @Schema(description = "Compatibility eligible count alias", example = "1000")
            int taskEligibleNumber,
            @Schema(description = "Compatibility success count alias", example = "998")
            int taskSuccessNumber,
            @Schema(description = "Compatibility non-success count alias", example = "2")
            int taskNonSuccessNumber,
            @Schema(description = "Minimum required workers observed or required for this task", example = "1")
            int minRequiredWorkerCount,
            @Schema(description = "Peak assigned workers observed for this task", example = "32")
            int peakAssignedWorkerCount,
            @Schema(description = "Flat success count alias for list callers", example = "998")
            int successCount,
            @Schema(description = "Flat eligible count alias for list callers", example = "1000")
            int eligibleCount,
            @Schema(description = "Flat execution profile alias", example = "STANDARD")
            String executionProfile,
            @Schema(description = "Flat workload class alias", example = "BULK")
            String workloadClass,
            @Schema(description = "Flat batch size alias", example = "50")
            int batchSize,
            @Schema(description = "Flat max runtime seconds alias; 0 means no explicit limit", example = "0")
            int maxRuntimeSeconds,
            @Schema(description = "Flat default retry count alias", example = "3")
            int defaultMaxRetryCount,
            @Schema(description = "Compatibility created timestamp alias", example = "2026-05-13 12:00:00")
            String createTime,
            @Schema(description = "Compatibility updated timestamp alias", example = "2026-05-13 12:05:00")
            String updateTime,
            @Schema(description = "Compatibility started timestamp alias", example = "2026-05-13 12:01:00")
            String startTime,
            @Schema(description = "Compatibility ended timestamp alias", example = "2026-05-13 12:10:00")
            String endTime,
            @Schema(description = "List-friendly updated timestamp alias", example = "2026-05-13 12:05:00")
            String updatedAt,
            @Schema(description = "Field-to-owner labels for this composite task response")
            Map<String, String> fieldSources
    ) {
    }

    @Schema(name = "ApiTaskShell", description = "Task shell fields returned by task creation")
    public record ApiTaskShell(
            @Schema(description = "Compatibility alias for taskId", example = "task-uuid")
            String id,
            @Schema(description = "Stable task identifier", example = "task-uuid")
            String taskId,
            @Schema(description = "Compatibility alias for taskId", example = "task-uuid")
            String tid,
            @Schema(description = "Server-derived display name", example = "demoApp-BATCH-task-uuid")
            String taskName,
            @Schema(description = "Tenant id. Current runtime is single-tenant with tenant-aware semantics.", example = "default")
            String tenantId,
            @Schema(description = "Project code that owns the task", example = "demoApp")
            String project,
            @Schema(description = "Task owner user id", example = "agent")
            String userId,
            @Schema(description = "Task runtime contract", allowableValues = {"SESSION", "BATCH"}, example = "BATCH")
            String contract,
            @Schema(description = "External source reference for the task shell", example = "import://demo/seed.ndjson")
            String sourceRef,
            @Schema(description = "Public task shared config with framework-owned security metadata stripped")
            Map<String, Object> sharedConfig,
            @Schema(description = "Resolved task execution policy")
            ApiTaskExecution execution,
            @Schema(description = "Compatibility alias for execution")
            ApiTaskExecution executionSpec,
            @Schema(description = "Field-to-owner labels for this shell response")
            Map<String, String> fieldSources
    ) {
    }

    @Schema(name = "ApiTaskExecution", description = "Task execution policy exposed by public task APIs")
    public record ApiTaskExecution(
            @Schema(description = "Execution profile", example = "STANDARD")
            String profile,
            @Schema(description = "Runtime workload optimization class", example = "BULK")
            String workloadClass,
            @Schema(description = "Preferred work batch size", example = "50")
            int batchSize,
            @Schema(description = "Maximum runtime seconds. 0 means no explicit limit.", example = "0")
            int maxRuntimeSeconds,
            @Schema(description = "Default retry count for newly ingested work items", example = "3")
            int defaultMaxRetryCount,
            @Schema(description = "Whether the task currently requires exclusive foreground worker scheduling", example = "true")
            boolean foreground
    ) {
    }

    @Schema(name = "ApiTaskCounters", description = "Task aggregate counters")
    public record ApiTaskCounters(
            @Schema(description = "Total target item count", example = "1000")
            int targetCount,
            @Schema(description = "Items eligible for execution", example = "1000")
            int eligibleCount,
            @Schema(description = "Items completed successfully", example = "998")
            int successCount,
            @Schema(description = "Items completed with non-success final outcome", example = "2")
            int nonSuccessCount,
            @Schema(description = "Minimum worker count required or observed", example = "1")
            int minRequiredWorkerCount,
            @Schema(description = "Peak assigned worker count", example = "32")
            int peakAssignedWorkerCount
    ) {
    }

    @Schema(name = "ApiTaskTimestamps", description = "Task lifecycle timestamps formatted by the server")
    public record ApiTaskTimestamps(
            @Schema(description = "Task created timestamp", example = "2026-05-13 12:00:00")
            String createdAt,
            @Schema(description = "Task last updated timestamp", example = "2026-05-13 12:05:00")
            String updatedAt,
            @Schema(description = "Task started timestamp", example = "2026-05-13 12:01:00")
            String startedAt,
            @Schema(description = "Task ended timestamp", example = "2026-05-13 12:10:00")
            String endedAt
    ) {
    }

    @Schema(name = "ApiTaskCommandOutcome", description = "Unified task command execution result")
    public record ApiTaskCommandOutcome(
            @Schema(description = "Task id", example = "task-uuid")
            String taskId,
            @Schema(description = "Command that was executed", example = "SEAL")
            String command,
            @Schema(description = "Whether the command was accepted by the task owner")
            boolean accepted,
            @Schema(description = "Post-command task status", example = "READY")
            String status,
            @Schema(description = "Post-command intake status", example = "SEALED")
            String intakeStatus,
            @Schema(description = "Post-command terminal reason, when terminal", example = "ALL_MESSAGES_SUCCEEDED")
            String terminalReason,
            @Schema(description = "Post-command hold reason, when blocked", example = "MANUAL_BLOCKED")
            String holdReason,
            @Schema(description = "Failure reason when accepted is false")
            String failureReason,
            @Schema(description = "Machine-readable failure reason code")
            String reasonCode
    ) {
    }

    @Schema(name = "ApiTaskResultItem", description = "Single ordered result row returned by live result reads and archive rows")
    public record ApiTaskResultItem(
            @Schema(description = "Task-local monotonically increasing sequence number", example = "42")
            long seq,
            @Schema(description = "Work item message id", example = "msg-001")
            String messageId,
            @Schema(description = "Event/capability code declared for this item", example = "crawler.fetch-page")
            String eventCode,
            @Schema(description = "Compatibility projection status for this work item", example = "SUCCESS")
            String status,
            @Schema(description = "Final reason when this row is final", example = "BUSINESS_SUCCESS")
            String finalReason,
            @Schema(description = "Retry attempts already consumed", example = "0")
            int retryCount,
            @Schema(description = "Maximum retry budget for this item", example = "3")
            int maxRetryCount,
            @Schema(description = "Worker id that produced the latest attempt", example = "worker-001")
            String workerId,
            @Schema(description = "Latest dispatch batch id", example = "batch-001")
            String batchId,
            @Schema(description = "Latest attempt id", example = "attempt-001")
            String attemptId,
            @Schema(description = "Payload reference when payload was stored out-of-line")
            String payloadRef,
            @Schema(description = "Item created timestamp", example = "2026-05-13 12:00:00")
            String createTime,
            @Schema(description = "Item assigned timestamp", example = "2026-05-13 12:01:00")
            String assignedTime,
            @Schema(description = "Item started timestamp", example = "2026-05-13 12:01:05")
            String startTime,
            @Schema(description = "Item completed timestamp", example = "2026-05-13 12:01:30")
            String completeTime,
            @Schema(description = "Item updated timestamp", example = "2026-05-13 12:01:30")
            String updateTime,
            @Schema(description = "Worker/business error code, if any")
            String errorCode,
            @Schema(description = "Worker/business error message, if any")
            String errorMessage,
            @Schema(description = "Opaque result payload returned by the worker")
            Map<String, Object> output
    ) {
    }

    @Schema(name = "ApiTaskResultWindow", description = "Sequential live result read window")
    public record ApiTaskResultWindow(
            @Schema(description = "Result read mode", allowableValues = {"LIVE", "ARCHIVE_READY"}, example = "LIVE")
            String mode,
            @Schema(description = "Task id", example = "task-uuid")
            String taskId,
            @Schema(description = "Whether the task has reached terminal lifecycle")
            boolean taskTerminal,
            @Schema(description = "Whether terminal archive content is ready")
            boolean archiveReady,
            @Schema(description = "Ordered result rows in this read window")
            List<ApiTaskResultItem> items,
            @Schema(description = "Sequence value callers should pass as afterSeq for the next read", example = "42")
            long nextAfterSeq,
            @Schema(description = "Whether more live rows are currently available after this window")
            boolean hasMore,
            @Schema(description = "Archive manifest URL when archive is ready", example = "/api/v1/tasks/task-uuid/results/archive")
            String archiveUrl
    ) {
    }

    @Schema(name = "ApiTaskResultArchive", description = "Terminal task result archive manifest")
    public record ApiTaskResultArchive(
            @Schema(description = "Whether archive content is ready")
            boolean ready,
            @Schema(description = "Task id", example = "task-uuid")
            String taskId,
            @Schema(description = "Archive row format", example = "ndjson")
            String format,
            @Schema(description = "Archive content type", example = "application/x-ndjson")
            String contentType,
            @Schema(description = "Archive content encoding", example = "gzip")
            String contentEncoding,
            @Schema(description = "Archived result item count", example = "1000")
            long itemCount,
            @Schema(description = "Archive byte size when known", example = "1048576", nullable = true)
            Long byteSize,
            @Schema(description = "Archive checksum when known", nullable = true)
            String checksum,
            @Schema(description = "Archive content download URL", example = "/api/v1/tasks/task-uuid/results/archive/content")
            String downloadUrl
    ) {
    }

    @Schema(name = "ApiTaskListResult", description = "Task list response body")
    public record ApiTaskListResult(
            @Schema(description = "Task list items")
            List<ApiTask> items,
            @Schema(description = "Number of returned items", example = "20")
            int total
    ) {
    }

    @Schema(name = "ApiTaskCreateOutcome", description = "Task shell create response body")
    public record ApiTaskCreateOutcome(
            @Schema(description = "Created task object")
            ApiTaskShell task,
            @Schema(description = "Created task id", example = "task-uuid")
            String taskId,
            @Schema(description = "Server-derived task display name")
            String taskName,
            @Schema(description = "Resolved project code", example = "demoApp")
            String project,
            @Schema(description = "Resolved owner user id", example = "agent")
            String userId,
            @Schema(description = "Authenticated principal id that created the task", example = "agent")
            String principalId,
            @Schema(description = "Resolved task contract", example = "BATCH")
            String contract,
            @Schema(description = "Resolved intake status", example = "OPEN")
            String intakeStatus,
            @Schema(description = "Human-readable operation message", example = "Task shell created")
            String message
    ) {
    }

    @Schema(name = "ApiTaskAppendOutcome", description = "Task item append response body")
    public record ApiTaskAppendOutcome(
            @Schema(description = "Task id", example = "task-uuid")
            String taskId,
            @Schema(description = "Number of items accepted into task intake", example = "100")
            int added,
            @Schema(description = "Task status after append, when available", example = "READY")
            String status,
            @Schema(description = "Task intake status after append", example = "OPEN")
            String intakeStatus,
            @Schema(description = "Human-readable operation message", example = "Items appended")
            String message
    ) {
    }

    @Schema(name = "ApiTaskSyncAppendOutcome", description = "Single-item synchronous append outcome")
    public record ApiTaskSyncAppendOutcome(
            @Schema(description = "Task id", example = "task-uuid")
            String taskId,
            @Schema(description = "Message id assigned to the appended item", example = "msg-001")
            String messageId,
            @Schema(description = "Whether a stable-final result was observed before the HTTP wait timed out")
            boolean synced,
            @Schema(description = "Whether the HTTP wait timed out before a stable-final result was observed")
            boolean timedOut,
            @Schema(description = "Resolved synchronous wait timeout in milliseconds", example = "5000")
            long timeoutMs,
            @Schema(description = "Stable-final item status when synced", example = "SUCCESS")
            String status,
            @Schema(description = "Stable-final reason when synced", example = "BUSINESS_SUCCESS")
            String finalReason,
            @Schema(description = "Opaque worker result payload when synced")
            Map<String, Object> output,
            @Schema(description = "Worker or business error code when synced and non-success")
            String errorCode,
            @Schema(description = "Worker or business error message when synced and non-success")
            String errorMessage
    ) {
    }

    @Schema(name = "ApiTaskUpdateOutcome", description = "Task shell update response body")
    public record ApiTaskUpdateOutcome(
            @Schema(description = "Task id", example = "task-uuid")
            String taskId,
            @Schema(description = "Task status after update", example = "NEW")
            String status,
            @Schema(description = "Task intake status after update", example = "OPEN")
            String intakeStatus,
            @Schema(description = "Human-readable operation message", example = "Task updated")
            String message
    ) {
    }

    @Schema(name = "ApiTaskGetResult", description = "Task detail response body")
    public record ApiTaskGetResult(
            @Schema(description = "Task detail object")
            ApiTask task,
            @Schema(description = "Server security/config view for this task")
            Map<String, Object> security
    ) {
    }
}
