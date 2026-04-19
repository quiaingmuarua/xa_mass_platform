package com.xa.mass.engine.storage;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;

import java.util.List;
import java.util.Optional;

/** Storage abstraction for tasks and task messages. */
public interface TaskStorage {

    void saveTask(Task task);

    Optional<Task> getTask(String taskId);

    boolean updateTask(Task task);

    boolean deleteTask(String taskId);

    List<Task> getAllTasks();

    List<Task> getTasksByStatus(TaskStatus status);

    /**
     * Returns all tasks belonging to the given project.
     *
     * <p>Default implementation scans {@link #getAllTasks()}; storage backends
     * should override this with an indexed query for better performance.
     */
    default List<Task> getTasksByProject(String project) {
        return getAllTasks().stream()
                .filter(t -> project != null && project.equals(t.getProject()))
                .collect(java.util.stream.Collectors.toList());
    }

    List<Task> getSchedulableTasks();

    void addTaskMessage(String taskId, TaskMsg taskMsg);

    List<TaskMsg> getTaskMessages(String taskId);

    Optional<TaskMsg> getTaskMessage(String taskId, String msgId);

    boolean updateTaskMessage(String taskId, TaskMsg taskMsg);

    void addTaskMessageAttempt(String taskId, String msgId, TaskMsgAttempt attempt);

    List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String msgId);

    Optional<TaskMsgAttempt> getLatestTaskMessageAttempt(String taskId, String msgId);

    boolean updateTaskMessageAttempt(String taskId, String msgId, TaskMsgAttempt attempt);

    TaskMessageStats getTaskMessageStats(String taskId);

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

        public long getTotal() {
            return total;
        }

        public long getSuccess() {
            return success;
        }

        /** FAILED count, excluding EXPIRED. */
        public long getFailed() {
            return failed;
        }

        /** EXPIRED count, excluding FAILED. */
        public long getExpired() {
            return expired;
        }

        public long getProcessing() {
            return processing;
        }

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

        public long getTotalAttempts() {
            return totalAttempts;
        }

        public long getActiveAttempts() {
            return activeAttempts;
        }

        public long getRunningAttempts() {
            return runningAttempts;
        }

        public long getFailedAttempts() {
            return failedAttempts;
        }

        public long getExpiredAttempts() {
            return expiredAttempts;
        }
    }
}
