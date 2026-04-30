package com.xa.mass.storage.api;

import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;

import java.util.List;
import java.util.Optional;

/**
 * Storage seam for task message and attempt detail.
 *
 * <p>Separated from {@link TaskStorage} so that the high-frequency
 * message/attempt write path can be routed to a different sink (e.g. a
 * trace/audit module) without touching the control-plane task storage or the
 * engine orchestration code. The current in-memory and JDBC implementations
 * implement both interfaces; future trace sinks implement only this one.
 */
public interface TaskDetailStore {

    void addTaskMessage(String taskId, TaskMsg taskMsg);

    List<TaskMsg> getTaskMessages(String taskId);

    List<TaskMsg> getTaskMessages(String taskId, int limit);

    List<TaskMsg> getNonFinalTaskMessages(String taskId);

    long countTaskMessages(String taskId);

    Optional<TaskMsg> getTaskMessage(String taskId, String messageId);

    boolean updateTaskMessage(String taskId, TaskMsg taskMsg);

    void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt);

    List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId);

    Optional<TaskMsgAttempt> getLatestTaskMessageAttempt(String taskId, String messageId);

    Optional<TaskMsgAttempt> getLatestActiveTaskMessageAttempt(String taskId, String messageId);

    boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt);

    TaskMessageStats getTaskMessageStats(String taskId);

    TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId);

    TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId);

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
}
