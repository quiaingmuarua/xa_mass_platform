package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.RuntimeTaskExecutor;

import java.util.concurrent.RejectedExecutionException;

/**
 * Owns task-level dispatch request submission, including coalesced delayed
 * retry wakeups.
 *
 * <p>This owner now only applies to session/interactive orchestration.
 * Batch/bulk retry visibility is driven directly by {@code TaskWorkRuntime}
 * plus runtime-ready recovery, so engine no longer layers a second delayed
 * task wakeup track on top of batch runtime truth.
 */
class TaskDispatchRequestService {

    private final TaskManager taskManager;
    private final RuntimeTaskExecutor retryWakeupExecutor;
    private final DelayedDispatchSchedule delayedDispatchSchedule;

    TaskDispatchRequestService(TaskManager taskManager,
                               RuntimeTaskExecutor retryWakeupExecutor,
                               DelayedDispatchSchedule delayedDispatchSchedule) {
        this.taskManager = taskManager;
        this.retryWakeupExecutor = retryWakeupExecutor;
        this.delayedDispatchSchedule = delayedDispatchSchedule;
    }

    void requestImmediate(Task task) {
        if (!isUsable(task)) {
            return;
        }
        delayedDispatchSchedule.remove(task.getTid());
        taskManager.publishTaskDispatchRequested(task);
    }

    void requestDelayed(Task task, long delayMillis) {
        if (!isUsable(task)) {
            return;
        }
        if (task.getContract() == TaskContract.BATCH) {
            return;
        }
        if (delayMillis <= 0L) {
            requestImmediate(task);
            return;
        }

        String taskId = task.getTid();
        long dueAtMillis = System.currentTimeMillis() + delayMillis;
        while (true) {
            Long currentDueAt = delayedDispatchSchedule.getDueAt(taskId);
            if (currentDueAt == null) {
                if (delayedDispatchSchedule.insertIfAbsent(taskId, dueAtMillis)) {
                    submitDelayedWakeup(taskId, dueAtMillis);
                    return;
                }
                continue;
            }
            if (dueAtMillis >= currentDueAt) {
                return;
            }
            if (delayedDispatchSchedule.replaceIfEqual(taskId, currentDueAt, dueAtMillis)) {
                submitDelayedWakeup(taskId, dueAtMillis);
                return;
            }
        }
    }

    void shutdown() {
        delayedDispatchSchedule.clear();
    }

    private void submitDelayedWakeup(String taskId, long scheduledDueAtMillis) {
        try {
            retryWakeupExecutor.submit(() -> runDelayedWakeup(taskId, scheduledDueAtMillis));
        } catch (RejectedExecutionException ignored) {
            delayedDispatchSchedule.removeIfEqual(taskId, scheduledDueAtMillis);
        }
    }

    private void runDelayedWakeup(String taskId, long scheduledDueAtMillis) {
        try {
            long sleepMillis = scheduledDueAtMillis - System.currentTimeMillis();
            if (sleepMillis > 0L) {
                Thread.sleep(sleepMillis);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        Long currentDueAtMillis = delayedDispatchSchedule.getDueAt(taskId);
        if (currentDueAtMillis == null || currentDueAtMillis.longValue() != scheduledDueAtMillis) {
            return;
        }
        if (!delayedDispatchSchedule.removeIfEqual(taskId, currentDueAtMillis)) {
            return;
        }

        Task refreshedTask = taskManager.getTask(taskId);
        if (refreshedTask == null || refreshedTask.getStatus().isFinal()) {
            return;
        }
        if (!taskManager.hasPendingDispatchableMessages(taskId)) {
            return;
        }
        taskManager.publishTaskDispatchRequested(refreshedTask);
    }

    private boolean isUsable(Task task) {
        return task != null && task.getTid() != null && !task.getTid().isBlank();
    }
}
