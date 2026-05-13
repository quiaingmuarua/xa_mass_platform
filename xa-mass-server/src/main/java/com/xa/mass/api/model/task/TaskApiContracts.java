package com.xa.mass.api.model.task;

import java.util.List;
import java.util.Map;

public final class TaskApiContracts {

    private TaskApiContracts() {
    }

    public record ApiTask(
            String id,
            String taskId,
            String tid,
            String taskName,
            String tenantId,
            String project,
            String userId,
            String contract,
            String status,
            String intakeStatus,
            String terminalReason,
            String holdReason,
            String sourceRef,
            ApiTaskExecution execution,
            ApiTaskExecution executionSpec,
            ApiTaskCounters counters,
            ApiTaskTimestamps timestamps,
            int taskTargetNumber,
            int taskEligibleNumber,
            int taskSuccessNumber,
            int taskNonSuccessNumber,
            int minRequiredWorkerCount,
            int peakAssignedWorkerCount,
            int successCount,
            int eligibleCount,
            String executionProfile,
            String workloadClass,
            int batchSize,
            int maxRuntimeSeconds,
            int defaultMaxRetryCount,
            String createTime,
            String updateTime,
            String startTime,
            String endTime,
            String updatedAt
    ) {
    }

    public record ApiTaskExecution(
            String profile,
            String workloadClass,
            int batchSize,
            int maxRuntimeSeconds,
            int defaultMaxRetryCount
    ) {
    }

    public record ApiTaskCounters(
            int targetCount,
            int eligibleCount,
            int successCount,
            int nonSuccessCount,
            int minRequiredWorkerCount,
            int peakAssignedWorkerCount
    ) {
    }

    public record ApiTaskTimestamps(
            String createdAt,
            String updatedAt,
            String startedAt,
            String endedAt
    ) {
    }

    public record ApiTaskCommandOutcome(
            String taskId,
            String command,
            boolean accepted,
            String status,
            String intakeStatus,
            String terminalReason,
            String holdReason,
            String failureReason,
            String reasonCode
    ) {
    }

    public record ApiTaskResultItem(
            long seq,
            String messageId,
            String eventCode,
            String status,
            String finalReason,
            int retryCount,
            int maxRetryCount,
            String workerId,
            String workerContextId,
            String batchId,
            String attemptId,
            String payloadRef,
            String createTime,
            String assignedTime,
            String startTime,
            String completeTime,
            String updateTime,
            String errorCode,
            String errorMessage,
            Map<String, Object> output
    ) {
    }

    public record ApiTaskResultWindow(
            String mode,
            String taskId,
            boolean taskTerminal,
            boolean archiveReady,
            List<ApiTaskResultItem> items,
            long nextAfterSeq,
            boolean hasMore,
            String archiveUrl
    ) {
    }

    public record ApiTaskResultArchive(
            boolean ready,
            String taskId,
            String format,
            String contentType,
            String contentEncoding,
            long itemCount,
            long byteSize,
            String checksum,
            String downloadUrl
    ) {
    }

    public record ApiTaskListResult(
            List<ApiTask> items,
            int total
    ) {
    }

    public record ApiTaskCreateOutcome(
            ApiTask task,
            String taskId,
            String taskName,
            String project,
            String userId,
            String principalId,
            String contract,
            String intakeStatus,
            String message
    ) {
    }

    public record ApiTaskAppendOutcome(
            String taskId,
            int added,
            String status,
            String intakeStatus,
            String message
    ) {
    }

    public record ApiTaskUpdateOutcome(
            String taskId,
            String status,
            String intakeStatus,
            String message
    ) {
    }

    public record ApiTaskGetResult(
            ApiTask task,
            Map<String, Object> security
    ) {
    }
}
