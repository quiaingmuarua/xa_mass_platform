package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.storage.api.TaskDetailStore;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-time compatibility projection helpers.
 *
 * <p>These helpers intentionally sit off the runtime hot path. They project a
 * bounded compatibility message view for callers that still read projection
 * storage after task-level runtime convergence has already happened.</p>
 */
@CompatibilityProjectionOnly
final class CompatibilityProjectionSupport {

    private CompatibilityProjectionSupport() {
    }

    static TaskDetailStore.TaskMessageProjection overlayTerminalTaskProjection(Task task,
                                                                               TaskDetailStore.TaskMessageProjection storedProjection) {
        if (task == null || storedProjection == null || isCompleted(storedProjection)) {
            return storedProjection;
        }
        if (task.getStatus() == null || !task.getStatus().isFinal()) {
            return storedProjection;
        }
        TaskTerminalReason terminalReason = task.getTerminalReason();
        if (terminalReason == null) {
            return storedProjection;
        }
        if (terminalReason != TaskTerminalReason.MANUAL_CANCELLED && !terminalReason.isPolicyDrivenStop()) {
            return storedProjection;
        }
        TaskMsgStatus finalStatus = storedProjection.status() != null && storedProjection.status().isProcessing()
                ? TaskMsgStatus.EXPIRED
                : TaskMsgStatus.FAILED;
        return new TaskDetailStore.TaskMessageProjection(
                storedProjection.messageId(),
                storedProjection.taskId(),
                storedProjection.input(),
                storedProjection.payloadRef(),
                finalStatus,
                storedProjection.assignedTime(),
                storedProjection.createTime(),
                LocalDateTime.now(),
                storedProjection.startTime(),
                LocalDateTime.now(),
                storedProjection.retryCount(),
                storedProjection.maxRetryCount(),
                terminalDetail(terminalReason),
                storedProjection.errorCode(),
                toMessageFinalReason(terminalReason),
                null,
                storedProjection.latestAttemptId(),
                storedProjection.latestAttemptWorkerId(),
                storedProjection.latestAttemptWorkerContextId(),
                storedProjection.latestAttemptBatchId()
        );
    }

    static List<TaskDetailStore.TaskMessageProjection> overlayTerminalTaskProjection(Task task,
                                                                                     List<TaskDetailStore.TaskMessageProjection> storedProjections) {
        if (storedProjections == null || storedProjections.isEmpty()) {
            return List.of();
        }
        if (task == null || task.getStatus() == null || !task.getStatus().isFinal()) {
            return List.copyOf(storedProjections);
        }
        List<TaskDetailStore.TaskMessageProjection> projected = new ArrayList<>(storedProjections.size());
        for (TaskDetailStore.TaskMessageProjection storedProjection : storedProjections) {
            projected.add(overlayTerminalTaskProjection(task, storedProjection));
        }
        return List.copyOf(projected);
    }

    static TaskDetailStore.TaskMessageProjection overlayActiveLeaseProjection(TaskDetailStore.TaskMessageProjection storedProjection,
                                                                              ActiveLeaseRecord activeLease,
                                                                              String taskId,
                                                                              String messageId) {
        if (activeLease == null) {
            return storedProjection;
        }
        TaskDetailStore.TaskMessageProjection base = storedProjection != null
                ? storedProjection
                : new TaskDetailStore.TaskMessageProjection(
                messageId,
                taskId,
                Map.of(),
                activeLease.payloadRef(),
                TaskMsgStatus.INIT,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        if (isCompleted(base)) {
            return base;
        }
        boolean attemptProjectionDiffers = !java.util.Objects.equals(base.latestAttemptWorkerId(), activeLease.workerId())
                || !java.util.Objects.equals(base.latestAttemptWorkerContextId(), activeLease.workerContextId())
                || !java.util.Objects.equals(base.latestAttemptBatchId(), activeLease.batchId());
        boolean needsAssignedStatus = base.status() == null || base.status() == TaskMsgStatus.INIT;
        boolean needsRetryProjection = base.retryCount() != Math.max(0, activeLease.retryCount());
        boolean needsAssignedTime = base.assignedTime() == null && activeLease.leasedAt() != null;
        boolean needsPayloadRefProjection = (base.payloadRef() == null || base.payloadRef().isBlank())
                && activeLease.payloadRef() != null
                && !activeLease.payloadRef().isBlank();
        if (!attemptProjectionDiffers
                && !needsAssignedStatus
                && !needsRetryProjection
                && !needsAssignedTime
                && !needsPayloadRefProjection) {
            return base;
        }
        LocalDateTime assignedTime = base.assignedTime();
        if (assignedTime == null && activeLease.leasedAt() != null) {
            assignedTime = LocalDateTime.ofInstant(activeLease.leasedAt(), ZoneId.systemDefault());
        }
        return new TaskDetailStore.TaskMessageProjection(
                base.messageId(),
                base.taskId(),
                base.input(),
                needsPayloadRefProjection ? activeLease.payloadRef() : base.payloadRef(),
                needsAssignedStatus ? TaskMsgStatus.ASSIGNED : base.status(),
                assignedTime,
                base.createTime(),
                needsAssignedStatus ? LocalDateTime.now() : base.updateTime(),
                base.startTime(),
                base.completeTime(),
                Math.max(0, activeLease.retryCount()),
                base.maxRetryCount(),
                base.errorMessage(),
                base.errorCode(),
                base.finalReason(),
                base.output(),
                base.latestAttemptId(),
                activeLease.workerId(),
                activeLease.workerContextId(),
                activeLease.batchId()
        );
    }

    static List<TaskDetailStore.TaskMessageProjection> overlayActiveLeaseProjection(List<TaskDetailStore.TaskMessageProjection> storedProjections,
                                                                                    List<ActiveLeaseRecord> activeLeases,
                                                                                    String taskId) {
        if ((storedProjections == null || storedProjections.isEmpty())
                && (activeLeases == null || activeLeases.isEmpty())) {
            return List.of();
        }
        Map<String, ActiveLeaseRecord> leaseByMessageId = new LinkedHashMap<>();
        if (activeLeases != null) {
            for (ActiveLeaseRecord activeLease : activeLeases) {
                if (activeLease == null || activeLease.messageId() == null || activeLease.messageId().isBlank()) {
                    continue;
                }
                leaseByMessageId.put(activeLease.messageId(), activeLease);
            }
        }
        if ((storedProjections == null || storedProjections.isEmpty()) && leaseByMessageId.isEmpty()) {
            return List.of();
        }
        List<TaskDetailStore.TaskMessageProjection> projected =
                new ArrayList<>(storedProjections != null ? storedProjections.size() : 0);
        java.util.Set<String> projectedMessageIds = new java.util.LinkedHashSet<>();
        if (storedProjections != null) {
            for (TaskDetailStore.TaskMessageProjection storedProjection : storedProjections) {
                if (storedProjection == null) {
                    continue;
                }
                projectedMessageIds.add(storedProjection.messageId());
                projected.add(overlayActiveLeaseProjection(
                        storedProjection,
                        leaseByMessageId.get(storedProjection.messageId()),
                        taskId,
                        storedProjection.messageId()
                ));
            }
        }
        for (ActiveLeaseRecord activeLease : leaseByMessageId.values()) {
            if (activeLease == null
                    || activeLease.messageId() == null
                    || activeLease.messageId().isBlank()
                    || projectedMessageIds.contains(activeLease.messageId())) {
                continue;
            }
            projected.add(overlayActiveLeaseProjection(
                    null,
                    activeLease,
                    taskId,
                    activeLease.messageId()
            ));
        }
        return List.copyOf(projected);
    }

    private static boolean isCompleted(TaskDetailStore.TaskMessageProjection projection) {
        return projection != null && projection.status() != null && projection.status().isFinal();
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        if (source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                throw new NullPointerException("map key");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static TaskMsgFinalReason toMessageFinalReason(TaskTerminalReason terminalReason) {
        return switch (terminalReason) {
            case MAX_RUNTIME_REACHED -> TaskMsgFinalReason.TIMEOUT;
            case RETRY_BUDGET_EXHAUSTED -> TaskMsgFinalReason.RETRY_EXHAUSTED;
            case MANUAL_CANCELLED, SUCCESS_RATE_REACHED -> TaskMsgFinalReason.MANUAL_CANCELLED;
            default -> TaskMsgFinalReason.MANUAL_CANCELLED;
        };
    }

    private static String terminalDetail(TaskTerminalReason terminalReason) {
        return switch (terminalReason) {
            case MAX_RUNTIME_REACHED -> "task terminated by max runtime policy";
            case SUCCESS_RATE_REACHED -> "task terminated by success-rate policy";
            case RETRY_BUDGET_EXHAUSTED -> "task terminated by retry-budget policy";
            case MANUAL_CANCELLED -> "task cancelled";
            default -> "task terminated";
        };
    }
}
