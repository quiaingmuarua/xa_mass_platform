package com.xa.mass.engine.runtime;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.TaskWorkAttemptIdSupport;
import com.xa.mass.task.runtime.ClaimedWorkItem;
import com.xa.mass.worker.runtime.selection.SelectedWorkerHandle;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TaskRuntimeDispatchBindingMapper {

    private TaskRuntimeDispatchBindingMapper() {
    }

    public static TaskDispatchBinding fromTaskRuntimeClaim(Task task, ClaimedWorkItem work) {
        if (work == null) {
            throw new IllegalArgumentException("claimed work item is required");
        }
        int retryCount = Math.max(0, work.attemptNo() - 1);
        String resolvedEventCode = resolvedEventCode(task, work.eventCode());
        return TaskDispatchBinding.workerLevelWithEvidence(
                resolvedTaskId(task, work.taskId()),
                work.messageId(),
                resolvedEventCode,
                work.payloadJson(),
                work.payloadRef(),
                retryCount,
                attemptId(work.messageId(), work.attemptNo(), work.workerId(), work.batchId()),
                work.attemptNo(),
                work.leaseToken(),
                work.workerId(),
                work.batchId(),
                work.workerGroupId(),
                work.workerReservationToken(),
                work.scoreBandClaimScore(),
                eventBindingKey(task, resolvedEventCode),
                workerCandidateSource(task)
        );
    }

    public static Map<String, Object> dispatchEvidence(SelectedWorkerHandle handle,
                                                       Task task,
                                                       String eventCode) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (handle != null) {
            evidence.put("workerGroupId", handle.workerGroupId());
        }
        String resolvedEventCode = resolvedEventCode(task, eventCode);
        evidence.put("eventBindingKey", eventBindingKey(task, resolvedEventCode));
        evidence.put("workerCandidateSource", workerCandidateSource(task));
        return evidence;
    }

    public static String eventBindingKey(Task task, String eventCode) {
        if (task == null || task.getProject() == null || task.getProject().isBlank()
                || eventCode == null || eventCode.isBlank()) {
            return null;
        }
        return task.getProject().trim() + ":" + eventCode.trim();
    }

    public static String workerCandidateSource(Task task) {
        if (task == null) {
            return null;
        }
        if (TaskSharedConfig.workerGroupSelector(task).isEmpty()) {
            return null;
        }
        String targetWorkerId = TaskSharedConfig.targetWorkerId(task);
        if (targetWorkerId != null && !targetWorkerId.isBlank()) {
            return "TARGET_WORKER";
        }
        return "GROUP_SELECTOR";
    }

    private static String attemptId(String messageId, int attemptNo, String workerId, String batchId) {
        return TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                messageId,
                attemptNo,
                workerId,
                batchId
        );
    }

    private static String resolvedEventCode(Task task, String eventCode) {
        if (eventCode != null && !eventCode.isBlank()) {
            return eventCode.trim();
        }
        return TaskSharedConfig.sdkEventCode(task);
    }

    private static String resolvedTaskId(Task task, String taskId) {
        if (task != null && task.getTid() != null && !task.getTid().isBlank()) {
            return task.getTid().trim();
        }
        return taskId;
    }

    private static String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }
}
