package com.xa.mass.engine.storage;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

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

    /**
     * Returns non-terminal tasks whose max-runtime deadline is due.
     *
     * <p>Default implementation scans tasks; storage backends should override
     * this with a deadline index so watchdog enforcement does not depend on
     * full task-list scans.
     */
    default List<Task> pollExpiredMaxRuntimeTasks(LocalDateTime now, int limit) {
        if (now == null || limit <= 0) {
            return List.of();
        }
        return getAllTasks().stream()
                .filter(task -> task != null
                        && task.getStatus() != null
                        && !task.getStatus().isFinal()
                        && task.getMaxRuntimeSeconds() > 0
                        && task.getStartTime() != null
                        && task.getStartTime().plusSeconds(task.getMaxRuntimeSeconds()).isBefore(now))
                .limit(limit)
                .collect(java.util.stream.Collectors.toList());
    }

    void addTaskMessage(String taskId, TaskMsg taskMsg);

    /**
     * Compatibility projection read for demo/tests and temporary internal
     * cleanup flows. Production detail/audit sinks should not depend on
     * materializing every message through the engine.
     */
    List<TaskMsg> getTaskMessages(String taskId);

    /**
     * Bounded compatibility projection read. This is not pagination and should
     * not become a high-volume detail API.
     */
    default List<TaskMsg> getTaskMessages(String taskId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return getTaskMessages(taskId).stream()
                .limit(limit)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Internal cleanup/convergence helper for logical messages that are not yet
     * final. Mainline runtime control flow should prefer this over full
     * task-message snapshots when it only needs pending work.
     */
    default List<TaskMsg> getNonFinalTaskMessages(String taskId) {
        return getTaskMessages(taskId).stream()
                .filter(taskMsg -> taskMsg != null
                        && taskMsg.getStatus() != null
                        && !taskMsg.getStatus().isFinal())
                .collect(java.util.stream.Collectors.toList());
    }

    default long countTaskMessages(String taskId) {
        return getTaskMessageStats(taskId).getTotal();
    }

    Optional<TaskMsg> getTaskMessage(String taskId, String messageId);

    boolean updateTaskMessage(String taskId, TaskMsg taskMsg);

    void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt);

    List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId);

    Optional<TaskMsgAttempt> getLatestTaskMessageAttempt(String taskId, String messageId);

    default Optional<TaskMsgAttempt> getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        List<TaskMsgAttempt> attempts = getTaskMessageAttempts(taskId, messageId);
        for (int i = attempts.size() - 1; i >= 0; i--) {
            TaskMsgAttempt attempt = attempts.get(i);
            if (attempt != null && attempt.getStatus() != null && attempt.getStatus().isActive()) {
                return Optional.of(attempt);
            }
        }
        return Optional.empty();
    }

    /**
     * Bounded audit helper for one logical task-message's attempt history.
     *
     * <p>This exists so validation/reconciliation paths can reason about
     * active-versus-final attempt state without materializing every
     * {@link TaskMsgAttempt} row for the message unless the backend has no
     * better index.</p>
     */
    default TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId) {
        List<TaskMsgAttempt> attempts = getTaskMessageAttempts(taskId, messageId);
        long totalAttempts = 0;
        long activeAttempts = 0;
        long runningAttempts = 0;
        long failedAttempts = 0;
        long expiredAttempts = 0;
        for (TaskMsgAttempt attempt : attempts) {
            if (attempt == null) {
                continue;
            }
            totalAttempts++;
            if (attempt.getStatus() != null && attempt.getStatus().isActive()) {
                activeAttempts++;
            }
            if (attempt.getStatus() == com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus.RUNNING) {
                runningAttempts++;
            }
            if (attempt.getStatus() == com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus.FAILED) {
                failedAttempts++;
            }
            if (attempt.getStatus() == com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus.EXPIRED) {
                expiredAttempts++;
            }
        }
        return new TaskMessageAttemptStats(
                totalAttempts,
                activeAttempts,
                runningAttempts,
                failedAttempts,
                expiredAttempts
        );
    }

    boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt);

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
