package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

public class SimpleTaskScheduler implements TaskScheduler {
    private static final Logger log = LoggerFactory.getLogger(SimpleTaskScheduler.class);

    @Override
    public SchedulingResult scheduleTask(Task task) {
        log.info("调度任务: {}", task.getTid());
        return SchedulingResult.success(Collections.emptyList());
    }

    @Override
    public List<SchedulingResult> scheduleTasks(List<Task> tasks) {
        log.info("批量调度任务: {}", tasks.size());
        return Collections.emptyList();
    }

    @Override
    public boolean handleTaskMsgCompletion(TaskMsg taskMsg) {
        log.info("任务消息完成: {}", taskMsg.getMsgId());
        return true;
    }

    @Override
    public boolean handleTaskMsgFailure(TaskMsg taskMsg, String errorMessage) {
        log.info("任务消息失败: {} error: {}", taskMsg.getMsgId(), errorMessage);
        return true;
    }

    @Override
    public boolean retryTaskMsg(TaskMsg taskMsg) {
        log.info("重试任务消息: {}", taskMsg.getMsgId());
        return true;
    }

    @Override
    public boolean cancelTask(String taskId) {
        log.info("取消任务: {}", taskId);
        return true;
    }

    @Override
    public boolean pauseTask(String taskId) {
        log.info("暂停任务: {}", taskId);
        return true;
    }

    @Override
    public boolean resumeTask(String taskId) {
        log.info("恢复任务: {}", taskId);
        return true;
    }
} 