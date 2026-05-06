package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * No-op scheduler used by tests and small local examples.
 */
public class SimpleTaskScheduler implements TaskScheduler {
    private static final Logger log = LoggerFactory.getLogger(SimpleTaskScheduler.class);

    @Override
    public SchedulingResult scheduleTask(Task task) {
        log.info("Scheduling task {}", task.getTid());
        return SchedulingResult.success(Collections.emptyList());
    }

    @Override
    public List<SchedulingResult> scheduleTasks(List<Task> tasks) {
        log.info("Scheduling {} tasks", tasks.size());
        return Collections.emptyList();
    }

    @Override
    public boolean retryTaskMsg(TaskMsg taskMsg) {
        log.info("Retrying task message {}", taskMsg.getMessageId());
        return true;
    }

    @Override
    public boolean cancelTask(String taskId) {
        log.info("Cancelling task {}", taskId);
        return true;
    }

    @Override
    public boolean pauseTask(String taskId) {
        log.info("Pausing task {}", taskId);
        return true;
    }

    @Override
    public boolean resumeTask(String taskId) {
        log.info("Resuming task {}", taskId);
        return true;
    }
}
