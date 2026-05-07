package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-time compatibility projection helpers.
 *
 * <p>These helpers intentionally sit off the runtime hot path. They project a
 * bounded TaskMsg view for callers that still read compatibility storage after
 * task-level runtime convergence has already happened.</p>
 */
final class CompatibilityProjectionSupport {

    private CompatibilityProjectionSupport() {
    }

    static TaskMsg overlayTerminalTaskView(Task task, TaskMsg storedProjection) {
        if (task == null || storedProjection == null || storedProjection.isCompleted()) {
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

        TaskMsg projected = copyOf(storedProjection);
        TaskMsgStatus finalStatus = storedProjection.getStatus() != null && storedProjection.getStatus().isProcessing()
                ? TaskMsgStatus.EXPIRED
                : TaskMsgStatus.FAILED;
        projected.forceFinalize(finalStatus, toMessageFinalReason(terminalReason), terminalDetail(terminalReason));
        return projected;
    }

    static List<TaskMsg> overlayTerminalTaskView(Task task, List<TaskMsg> storedProjections) {
        if (storedProjections == null || storedProjections.isEmpty()) {
            return List.of();
        }
        if (task == null || task.getStatus() == null || !task.getStatus().isFinal()) {
            return List.copyOf(storedProjections);
        }
        List<TaskMsg> projected = new ArrayList<>(storedProjections.size());
        for (TaskMsg storedProjection : storedProjections) {
            projected.add(overlayTerminalTaskView(task, storedProjection));
        }
        return List.copyOf(projected);
    }

    private static TaskMsg copyOf(TaskMsg source) {
        TaskMsg copy = new TaskMsg(source.getMessageId(), source.getTaskId(), source.getInput(), source.getPayloadRef());
        copy.setStatus(source.getStatus());
        copy.setAssignedTime(source.getAssignedTime());
        copy.setCreateTime(source.getCreateTime());
        copy.setUpdateTime(source.getUpdateTime());
        copy.setStartTime(source.getStartTime());
        copy.setCompleteTime(source.getCompleteTime());
        copy.setRetryCount(source.getRetryCount());
        copy.setMaxRetryCount(source.getMaxRetryCount());
        copy.setErrorMessage(source.getErrorMessage());
        copy.setErrorCode(source.getErrorCode());
        copy.setFinalReason(source.getFinalReason());
        copy.setOutput(copyMap(source.getOutput()));
        copy.applyLatestAttemptProjection(
                source.latestAttemptId(),
                source.getLatestAttemptWorkerId(),
                source.getLatestAttemptWorkerContextId(),
                source.getLatestAttemptBatchId()
        );
        return copy;
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? null : Map.copyOf(source);
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
