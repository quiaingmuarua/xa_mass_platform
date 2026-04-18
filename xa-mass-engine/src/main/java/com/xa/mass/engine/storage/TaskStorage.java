package com.xa.mass.engine.storage;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;

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

    TaskMessageStats getTaskMessageStats(String taskId);

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
} 
