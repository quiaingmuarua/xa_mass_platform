package com.xa.mass.engine.strategy;

import com.xa.mass.engine.model.task.Task;
import com.xa.mass.engine.model.TaskMsg;

import java.util.Collections;
import java.util.List;

public class SimpleTaskScheduler implements TaskScheduler {
    @Override
    public SchedulingResult scheduleTask(Task task) {
        System.out.println("调度任务: " + task.getTid());
        return SchedulingResult.success(Collections.emptyList());
    }

    @Override
    public List<SchedulingResult> scheduleTasks(List<Task> tasks) {
        System.out.println("批量调度任务: " + tasks.size());
        return Collections.emptyList();
    }

    @Override
    public boolean handleTaskMsgCompletion(TaskMsg taskMsg) {
        System.out.println("任务消息完成: " + taskMsg.getMsgId());
        return true;
    }

    @Override
    public boolean handleTaskMsgFailure(TaskMsg taskMsg, String errorMessage) {
        System.out.println("任务消息失败: " + taskMsg.getMsgId() + " error: " + errorMessage);
        return true;
    }

    @Override
    public boolean retryTaskMsg(TaskMsg taskMsg) {
        System.out.println("重试任务消息: " + taskMsg.getMsgId());
        return true;
    }

    @Override
    public boolean cancelTask(String taskId) {
        System.out.println("取消任务: " + taskId);
        return true;
    }

    @Override
    public boolean pauseTask(String taskId) {
        System.out.println("暂停任务: " + taskId);
        return true;
    }

    @Override
    public boolean resumeTask(String taskId) {
        System.out.println("恢复任务: " + taskId);
        return true;
    }
} 