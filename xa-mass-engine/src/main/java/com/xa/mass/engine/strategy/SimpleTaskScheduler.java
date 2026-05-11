package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Task;
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
        return SchedulingResult.success(1);
    }

    @Override
    public List<SchedulingResult> scheduleTasks(List<Task> tasks) {
        log.info("Scheduling {} tasks", tasks.size());
        return Collections.emptyList();
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
