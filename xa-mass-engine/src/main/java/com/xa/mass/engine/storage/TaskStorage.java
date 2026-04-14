package com.xa.mass.engine.storage;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;

import java.util.List;
import java.util.Optional;

/**
 * 任务存储接口
 * 提供任务和任务消息的存储抽象能力
 */
public interface TaskStorage {

    /**
     * 保存任务
     */
    void saveTask(Task task);

    /**
     * 根据ID获取任务
     */
    Optional<Task> getTask(String taskId);

    /**
     * 更新任务
     */
    boolean updateTask(Task task);

    /**
     * 删除任务
     */
    boolean deleteTask(String taskId);

    /**
     * 获取所有任务
     */
    List<Task> getAllTasks();

    /**
     * 根据状态获取任务
     */
    List<Task> getTasksByStatus(String status);

    /**
     * 获取可调度的任务
     */
    List<Task> getSchedulableTasks();

    /**
     * 添加任务消息
     */
    void addTaskMessage(String taskId, TaskMsg taskMsg);

    /**
     * 获取任务的所有消息
     */
    List<TaskMsg> getTaskMessages(String taskId);

    Optional<TaskMsg> getTaskMessage(String taskId, String msgId);

    boolean updateTaskMessage(String taskId, TaskMsg taskMsg);

    /**
     * 获取任务消息统计
     */
    TaskMessageStats getTaskMessageStats(String taskId);

    /**
     * 任务消息统计
     */
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

        /** FAILED 状态的消息数，不含 EXPIRED。 */
        public long getFailed() {
            return failed;
        }

        /** EXPIRED 状态的消息数，不含 FAILED。 */
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
