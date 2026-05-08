package com.xa.mass.storage.api;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Storage seam for task-message compatibility projection and attempt detail.
 *
 * <p>Separated from {@link TaskStorage} so that the high-frequency
 * message/attempt write path can be routed to a different sink (e.g. a
 * trace/audit module) without touching the control-plane task storage or the
 * engine orchestration code. The current in-memory and JDBC implementations
 * implement both interfaces; future trace sinks implement only this one.
 *
 * <p>Callers should treat this seam in two tiers:
 * runtime-essential compatibility projection helpers used by result repair and
 * bounded convergence, and shell/debug reads that must not grow into
 * pagination, analytics, or durable-history contracts.
 *
 * <p>{@link TaskMsg} and {@link TaskMsgAttempt} remain bounded compatibility
 * residue shapes here. This seam must not be treated as a public SDK/server
 * read-model contract or as the runtime-hot-path source of truth.</p>
 */
public interface TaskDetailStore {

    /**
     * Engine-facing bounded projection upsert.
     *
     * <p>Mainline engine code should prefer this helper over open-coding
     * add/update CRUD flow so the residue seam stays a projection sink rather
     * than a message-CRUD owner. Implementations should override this helper
     * directly once they can store neutral projection records natively, instead
     * of routing back through {@link TaskMsg} materialization.</p>
     */
    @CompatibilityProjectionOnly
    default boolean upsertTaskMessageProjection(String taskId, TaskMessageProjection projection) {
        return upsertTaskMessageProjection(taskId, projection != null ? projection.toCompatibilityProjection() : null);
    }

    /**
     * Engine-facing bounded projection snapshot read.
     *
     * <p>Mainline engine code should prefer this neutral snapshot view over
     * reading {@link TaskMsg} directly.</p>
     */
    @CompatibilityProjectionOnly
    default Optional<TaskMessageProjection> getTaskMessageProjection(String taskId, String messageId) {
        return getTaskMessage(taskId, messageId).map(TaskMessageProjection::fromCompatibilityProjection);
    }

    @CompatibilityProjectionOnly
    default List<TaskMessageProjection> getTaskMessageProjections(String taskId) {
        return getTaskMessages(taskId).stream()
                .map(TaskMessageProjection::fromCompatibilityProjection)
                .toList();
    }

    @CompatibilityProjectionOnly
    default List<TaskMessageProjection> getTaskMessageProjections(String taskId, int limit) {
        return getTaskMessages(taskId, limit).stream()
                .map(TaskMessageProjection::fromCompatibilityProjection)
                .toList();
    }

    @CompatibilityProjectionOnly
    @Deprecated(forRemoval = true)
    default boolean upsertTaskMessageProjection(String taskId, TaskMsg taskMsg) {
        if (taskMsg == null) {
            return false;
        }
        if (updateTaskMessage(taskId, taskMsg)) {
            return true;
        }
        addTaskMessage(taskId, taskMsg);
        return true;
    }

    /**
     * Engine-facing bounded latest-attempt projection upsert.
     *
     * <p>This helper exists so dispatch/result/expiry convergence can treat
     * attempt residue as one bounded write step instead of a CRUD lifecycle.</p>
     */
    @CompatibilityProjectionOnly
    default boolean upsertTaskMessageAttemptProjection(String taskId,
                                                       String messageId,
                                                       TaskMessageAttemptProjection projection) {
        return upsertTaskMessageAttemptProjection(taskId, messageId,
                projection != null ? projection.toCompatibilityProjection() : null);
    }

    @CompatibilityProjectionOnly
    default Optional<TaskMessageAttemptProjection> getLatestTaskMessageAttemptProjection(String taskId,
                                                                                         String messageId) {
        return getLatestTaskMessageAttempt(taskId, messageId)
                .map(TaskMessageAttemptProjection::fromCompatibilityProjection);
    }

    @CompatibilityProjectionOnly
    @Deprecated(forRemoval = true)
    default boolean upsertTaskMessageAttemptProjection(String taskId, String messageId, TaskMsgAttempt attempt) {
        if (attempt == null) {
            return false;
        }
        if (updateTaskMessageAttempt(taskId, messageId, attempt)) {
            return true;
        }
        addTaskMessageAttempt(taskId, messageId, attempt);
        return true;
    }

    /** Compatibility projection writes used by bounded residue repair paths. */
    @CompatibilityProjectionOnly
    @Deprecated(forRemoval = true)
    void addTaskMessage(String taskId, TaskMsg taskMsg);

    /** Bounded compatibility reads; callers must not treat these as public history APIs. */
    @CompatibilityProjectionOnly
    @Deprecated(forRemoval = true)
    List<TaskMsg> getTaskMessages(String taskId);

    @CompatibilityProjectionOnly
    @Deprecated(forRemoval = true)
    List<TaskMsg> getTaskMessages(String taskId, int limit);

    @CompatibilityProjectionOnly
    @Deprecated(forRemoval = true)
    List<TaskMsg> getNonFinalTaskMessages(String taskId);

    @Deprecated(forRemoval = true)
    long countTaskMessages(String taskId);

    @CompatibilityProjectionOnly
    @Deprecated(forRemoval = true)
    Optional<TaskMsg> getTaskMessage(String taskId, String messageId);

    /** Compatibility projection repair/update only. */
    @CompatibilityProjectionOnly
    @Deprecated(forRemoval = true)
    boolean updateTaskMessage(String taskId, TaskMsg taskMsg);

    /** Attempt-level compatibility projection writes used by bounded residue repair paths. */
    @CompatibilityProjectionOnly
    @Deprecated(forRemoval = true)
    void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt);

    /** Bounded compatibility reads; callers must not treat these as public history APIs. */
    @CompatibilityProjectionOnly
    @Deprecated(forRemoval = true)
    List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId);

    @CompatibilityProjectionOnly
    @Deprecated(forRemoval = true)
    Optional<TaskMsgAttempt> getLatestTaskMessageAttempt(String taskId, String messageId);

    @CompatibilityProjectionOnly
    @Deprecated(forRemoval = true)
    Optional<TaskMsgAttempt> getLatestActiveTaskMessageAttempt(String taskId, String messageId);

    @CompatibilityProjectionOnly
    @Deprecated(forRemoval = true)
    boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt);

    /** Aggregate diagnostics only; these stats do not promote the residue rows to runtime truth. */
    TaskMessageStats getTaskMessageStats(String taskId);

    TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId);

    TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId);

    @CompatibilityProjectionOnly
    record TaskMessageProjection(String messageId,
                                 String taskId,
                                 Map<String, Object> input,
                                 String payloadRef,
                                 TaskMsgStatus status,
                                 LocalDateTime assignedTime,
                                 LocalDateTime createTime,
                                 LocalDateTime updateTime,
                                 LocalDateTime startTime,
                                 LocalDateTime completeTime,
                                 int retryCount,
                                 int maxRetryCount,
                                 String errorMessage,
                                 String errorCode,
                                 TaskMsgFinalReason finalReason,
                                 Map<String, Object> output,
                                 String latestAttemptId,
                                 String latestAttemptWorkerId,
                                 String latestAttemptWorkerContextId,
                                 String latestAttemptBatchId) {

        public TaskMessageProjection {
            input = copyMap(input);
            output = copyMap(output);
            retryCount = Math.max(0, retryCount);
            maxRetryCount = Math.max(0, maxRetryCount);
        }

        public static TaskMessageProjection fromCompatibilityProjection(TaskMsg projection) {
            if (projection == null) {
                return null;
            }
            return new TaskMessageProjection(
                    projection.getMessageId(),
                    projection.getTaskId(),
                    projection.getInput(),
                    projection.getPayloadRef(),
                    projection.getStatus(),
                    projection.getAssignedTime(),
                    projection.getCreateTime(),
                    projection.getUpdateTime(),
                    projection.getStartTime(),
                    projection.getCompleteTime(),
                    projection.getRetryCount(),
                    projection.getMaxRetryCount(),
                    projection.getErrorMessage(),
                    projection.getErrorCode(),
                    projection.getFinalReason(),
                    projection.getOutput(),
                    projection.latestAttemptId(),
                    projection.getLatestAttemptWorkerId(),
                    projection.getLatestAttemptWorkerContextId(),
                    projection.getLatestAttemptBatchId()
            );
        }

        public TaskMsg toCompatibilityProjection() {
            TaskMsg taskMsg = payloadRef == null || payloadRef.isBlank()
                    ? new TaskMsg(messageId, taskId, input)
                    : new TaskMsg(messageId, taskId, input, payloadRef);
            if (status != null) {
                taskMsg.setStatus(status);
            }
            taskMsg.setAssignedTime(assignedTime);
            taskMsg.setCreateTime(createTime);
            taskMsg.setUpdateTime(updateTime);
            taskMsg.setStartTime(startTime);
            taskMsg.setCompleteTime(completeTime);
            taskMsg.setRetryCount(retryCount);
            taskMsg.setMaxRetryCount(maxRetryCount);
            taskMsg.setErrorMessage(errorMessage);
            taskMsg.setErrorCode(errorCode);
            taskMsg.setFinalReason(finalReason);
            taskMsg.setOutput(copyMap(output));
            taskMsg.applyLatestAttemptProjection(
                    latestAttemptId,
                    latestAttemptWorkerId,
                    latestAttemptWorkerContextId,
                    latestAttemptBatchId
            );
            return taskMsg;
        }
    }

    @CompatibilityProjectionOnly
    record TaskMessageAttemptProjection(String attemptId,
                                        String taskId,
                                        String messageId,
                                        int attemptNo,
                                        String workerId,
                                        String workerContextId,
                                        String batchId,
                                        TaskMsgAttemptStatus status,
                                        TaskMsgAttemptFinalReason finalReason,
                                        String errorMessage,
                                        String errorCode,
                                        Map<String, Object> output) {

        public TaskMessageAttemptProjection {
            output = copyMap(output);
            attemptNo = Math.max(0, attemptNo);
        }

        public static TaskMessageAttemptProjection fromCompatibilityProjection(TaskMsgAttempt projection) {
            if (projection == null) {
                return null;
            }
            return new TaskMessageAttemptProjection(
                    projection.getAttemptId(),
                    projection.getTaskId(),
                    projection.getMessageId(),
                    projection.getAttemptNo(),
                    projection.getWorkerId(),
                    projection.getWorkerContextId(),
                    projection.getBatchId(),
                    projection.getStatus(),
                    projection.getFinalReason(),
                    projection.getErrorMessage(),
                    projection.getErrorCode(),
                    projection.getOutput()
            );
        }

        public TaskMsgAttempt toCompatibilityProjection() {
            TaskMsgAttempt attempt = new TaskMsgAttempt(
                    attemptId,
                    taskId,
                    messageId,
                    attemptNo
            );
            attempt.setWorkerId(workerId);
            attempt.setWorkerContextId(workerContextId);
            attempt.setBatchId(batchId);
            attempt.setStatus(status);
            attempt.setFinalReason(finalReason);
            attempt.setErrorMessage(errorMessage);
            attempt.setErrorCode(errorCode);
            attempt.setOutput(copyMap(output));
            return attempt;
        }
    }

    class TaskMessageStats {
        private final long total;
        private final long success;
        private final long failed;
        private final long expired;
        private final long processing;

        public TaskMessageStats(long total, long success, long failed, long expired, long processing) {
            this.total = total;
            this.success = success;
            this.failed = failed;
            this.expired = expired;
            this.processing = processing;
        }

        public long getTotal() { return total; }
        public long getSuccess() { return success; }
        public long getFailed() { return failed; }
        public long getExpired() { return expired; }
        public long getProcessing() { return processing; }

        public double getSuccessRate() {
            return total > 0 ? (double) success / total * 100 : 0.0;
        }

        public double getFailureRate() {
            return total > 0 ? (double) (failed + expired) / total * 100 : 0.0;
        }
    }

    class TaskMessageAttemptStats {
        private final long totalAttempts;
        private final long activeAttempts;
        private final long runningAttempts;
        private final long failedAttempts;
        private final long expiredAttempts;

        public TaskMessageAttemptStats(long totalAttempts,
                                       long activeAttempts,
                                       long runningAttempts,
                                       long failedAttempts,
                                       long expiredAttempts) {
            this.totalAttempts = totalAttempts;
            this.activeAttempts = activeAttempts;
            this.runningAttempts = runningAttempts;
            this.failedAttempts = failedAttempts;
            this.expiredAttempts = expiredAttempts;
        }

        public long getTotalAttempts() { return totalAttempts; }
        public long getActiveAttempts() { return activeAttempts; }
        public long getRunningAttempts() { return runningAttempts; }
        public long getFailedAttempts() { return failedAttempts; }
        public long getExpiredAttempts() { return expiredAttempts; }
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? null : Map.copyOf(source);
    }
}
