package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Task;

import java.util.List;

/**
 * SPI for pluggable task scheduling.
 *
 * <p>The default implementation ({@link SimpleTaskScheduler}) is a no-op placeholder.
 * Wire a real implementation via
 * {@code EngineConfig#setScheduler} or {@code MassEngineBuilder#scheduler(TaskScheduler)}
 * when you need custom scheduling logic (e.g. Quartz, Spring Scheduler).
 *
 * <p><b>Note:</b> These methods are advisory hooks â€?the mainline dispatch loop in
 * {@code TaskAssignWorker} does not call them directly. They are reserved for future
 * integration with external scheduling systems.
 */
public interface TaskScheduler {

    SchedulingResult scheduleTask(Task task);

    List<SchedulingResult> scheduleTasks(List<Task> tasks);

    boolean retryTaskDispatchUnit(String taskId, String messageId);

    boolean cancelTask(String taskId);

    boolean pauseTask(String taskId);

    boolean resumeTask(String taskId);

    class SchedulingResult {
        private final boolean success;
        private final String message;
        private final int scheduledCount;

        public SchedulingResult(boolean success, String message, int scheduledCount) {
            this.success = success;
            this.message = message;
            this.scheduledCount = Math.max(0, scheduledCount);
        }

        public static SchedulingResult success() {
            return new SchedulingResult(true, "scheduled", 0);
        }

        public static SchedulingResult success(int scheduledCount) {
            return new SchedulingResult(true, "scheduled", scheduledCount);
        }

        public static SchedulingResult failure(String message) {
            return new SchedulingResult(false, message, 0);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public int getScheduledCount() {
            return scheduledCount;
        }
    }
} 
