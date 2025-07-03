package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;

import java.util.List;

/**
 * 任务调度器接口
 * 负责整个任务调度流程
 */
public interface TaskScheduler {

    /**
     * 调度任务
     *
     * @param task 要调度的任务
     * @return 调度结果
     */
    SchedulingResult scheduleTask(Task task);

    /**
     * 批量调度任务
     *
     * @param tasks 要调度的任务列表
     * @return 调度结果列表
     */
    List<SchedulingResult> scheduleTasks(List<Task> tasks);

    /**
     * 处理任务消息完成回调
     *
     * @param taskMsg 完成的任务消息
     * @return 处理结果
     */
    boolean handleTaskMsgCompletion(TaskMsg taskMsg);

    /**
     * 处理任务消息失败回调
     *
     * @param taskMsg 失败的任务消息
     * @param errorMessage 错误信息
     * @return 处理结果
     */
    boolean handleTaskMsgFailure(TaskMsg taskMsg, String errorMessage);

    /**
     * 重试失败的任务消息
     *
     * @param taskMsg 要重试的任务消息
     * @return 重试结果
     */
    boolean retryTaskMsg(TaskMsg taskMsg);

    /**
     * 取消任务
     *
     * @param taskId 任务ID
     * @return 取消结果
     */
    boolean cancelTask(String taskId);

    /**
     * 暂停任务
     *
     * @param taskId 任务ID
     * @return 暂停结果
     */
    boolean pauseTask(String taskId);

    /**
     * 恢复任务
     *
     * @param taskId 任务ID
     * @return 恢复结果
     */
    boolean resumeTask(String taskId);

    /**
     * 调度结果
     */
    class SchedulingResult {
        private final boolean success;
        private final String message;
        private final List<TaskMsg> scheduledMessages;
        private final int scheduledCount;

        public SchedulingResult(boolean success, String message, List<TaskMsg> scheduledMessages) {
            this.success = success;
            this.message = message;
            this.scheduledMessages = scheduledMessages;
            this.scheduledCount = scheduledMessages != null ? scheduledMessages.size() : 0;
        }

        public static SchedulingResult success(List<TaskMsg> scheduledMessages) {
            return new SchedulingResult(true, "调度成功", scheduledMessages);
        }

        public static SchedulingResult failure(String message) {
            return new SchedulingResult(false, message, null);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public List<TaskMsg> getScheduledMessages() {
            return scheduledMessages;
        }

        public int getScheduledCount() {
            return scheduledCount;
        }
    }
} 