package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.util.TraceEventLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single-threaded assignment queue worker.
 *
 * <p>This component drains assignment requests and retries unmatched dispatch
 * attempts. Its listener callbacks describe queue-processing progress only,
 * not task business completion.
 */
public class TaskAssignWorker {
    private static final Logger log = LoggerFactory.getLogger(TaskAssignWorker.class);
    private static final long DEFAULT_RETRY_DELAY_MILLIS = 1000L;

    private final TaskWorkerAssignListener workerAssignListener;
    private final long retryDelayMillis;
    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    private final List<TaskAssignmentQueueListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private final AtomicInteger scheduledRetryCount = new AtomicInteger(0);
    private final Set<String> trackedTaskIds = ConcurrentHashMap.newKeySet();
    private final Set<String> deferredRequeueTaskIds = ConcurrentHashMap.newKeySet();

    private volatile boolean running = true;
    private ExecutorService executor;
    private ScheduledExecutorService retryExecutor;

    public TaskAssignWorker(TaskWorkerAssignListener workerAssignListener) {
        this(workerAssignListener, DEFAULT_RETRY_DELAY_MILLIS);
    }

    public TaskAssignWorker(TaskWorkerAssignListener workerAssignListener, long retryDelayMillis) {
        this.workerAssignListener = workerAssignListener;
        this.retryDelayMillis = retryDelayMillis;
    }

    public void addAssignmentQueueListener(TaskAssignmentQueueListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void start() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "TaskAssignWorker");
            t.setDaemon(true);
            return t;
        });
        retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TaskAssignWorkerRetry");
            t.setDaemon(true);
            return t;
        });

        executor.submit(() -> {
            while (running) {
                Task task = null;
                try {
                    task = queue.take();
                    String taskId = task != null ? task.getTid() : null;
                    TaskStatus initialStatus = task.getStatus();
                    if (initialStatus == TaskStatus.READY || initialStatus == TaskStatus.RUNNING) {
                        boolean assigned = workerAssignListener.onTaskAssign(task);
                        if (running && !assigned && task.getStatus() == initialStatus) {
                            scheduleRetry(task, initialStatus);
                        } else if (!enqueueDeferredRequeueIfRequested(task)) {
                            releaseTrackedTask(taskId);
                            notifyAssignmentProcessed(task);
                        } else {
                            emitQueueSnapshot(task, task.getStatus(), "REQUEUE_ENQUEUED", null,
                                    "deferred requeue requested while assignment was still processing", "SUCCESS");
                        }
                    } else {
                        clearDeferredRequeue(taskId);
                        releaseTrackedTask(taskId);
                        emitQueueSnapshot(task, initialStatus, "SKIPPED_NON_DISPATCHABLE", null,
                                "task skipped because status is not READY or RUNNING", "SKIPPED");
                        notifyAssignmentProcessed(task);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    clearDeferredRequeue(task != null ? task.getTid() : null);
                    releaseTrackedTask(task != null ? task.getTid() : null);
                    log.error("TaskAssignWorker error: {}", e.getMessage(), e);
                }
            }
        });
    }

    public CompletableFuture<Void> submitAll(List<Task> tasks) {
        List<Task> acceptedTasks = new ArrayList<>();
        for (Task task : tasks) {
            if (!trackTask(task)) {
                emitQueueSnapshot(task, task != null ? task.getStatus() : null, "DEDUP_SKIPPED", null,
                        "task is already queued, processing, or waiting retry", "SKIPPED");
                continue;
            }
            acceptedTasks.add(task);
        }

        if (!acceptedTasks.isEmpty()) {
            pendingTasks.addAndGet(acceptedTasks.size());
            for (Task task : acceptedTasks) {
                queue.offer(task);
                emitQueueSnapshot(task, task != null ? task.getStatus() : null, "SUBMITTED", null,
                        "task submitted to assignment queue", "SUCCESS");
            }
        }

        // Use the common pool instead of `executor`: the task-processing loop already occupies
        // the single thread in `executor`, so submitting this polling future to the same
        // executor would queue it behind a loop that never exits, causing a deadlock.
        return CompletableFuture.runAsync(() -> {
            while (pendingTasks.get() > 0 && running) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    public boolean submit(Task task) {
        return submit(task, false);
    }

    private boolean submit(Task task, boolean trackedBatchSubmission) {
        if (!trackTask(task)) {
            if (markDeferredRequeue(task)) {
                emitQueueSnapshot(task, task != null ? task.getStatus() : null, "REQUEUE_MARKED", null,
                        "task requested another dispatch while an assignment cycle is still in progress", "DEFERRED");
            } else {
                emitQueueSnapshot(task, task != null ? task.getStatus() : null, "DEDUP_SKIPPED", null,
                        "task is already queued, processing, or waiting retry", "SKIPPED");
            }
            return false;
        }
        if (trackedBatchSubmission) {
            pendingTasks.incrementAndGet();
        }
        queue.offer(task);
        emitQueueSnapshot(task, task != null ? task.getStatus() : null, "SUBMITTED", null,
                "task submitted to assignment queue", "SUCCESS");
        return true;
    }

    private void scheduleRetry(Task task, TaskStatus expectedStatus) {
        if (retryExecutor == null) {
            return;
        }
        scheduledRetryCount.incrementAndGet();
        TraceEventLogger.assignmentRetryScheduled(
                task.getTid(),
                expectedStatus,
                "NO_ASSIGNMENT_RESULT",
                "TaskAssignWorker",
                "task remained eligible after assignment attempt",
                retryDelayMillis
        );
        emitQueueSnapshot(task, expectedStatus, "RETRY_SCHEDULED", retryDelayMillis,
                "task remained eligible after assignment attempt", "SCHEDULED");
        retryExecutor.schedule(() -> {
            scheduledRetryCount.updateAndGet(current -> current > 0 ? current - 1 : 0);
            if (running && task.getStatus() == expectedStatus) {
                queue.offer(task);
                emitQueueSnapshot(task, expectedStatus, "RETRY_ENQUEUED", retryDelayMillis,
                        "delayed retry enqueued task back into assignment queue", "SUCCESS");
                return;
            }
            releaseTrackedTask(task.getTid());
            emitQueueSnapshot(task, task.getStatus(), "RETRY_DROPPED", retryDelayMillis,
                    "delayed retry was dropped because task is no longer eligible", "SKIPPED");
        }, retryDelayMillis, TimeUnit.MILLISECONDS);
    }

    private void notifyAssignmentProcessed(Task task) {
        int previous = pendingTasks.getAndUpdate(current -> current > 0 ? current - 1 : 0);
        int remaining = previous > 0 ? previous - 1 : 0;
        emitQueueSnapshot(task, task != null ? task.getStatus() : null, "PROCESSED", null,
                "assignment attempt finished processing", "SUCCESS");
        listeners.forEach(l -> l.onTaskAssignmentProcessed(task));

        if (previous > 0 && remaining == 0) {
            emitQueueSnapshot(task, task != null ? task.getStatus() : null, "DRAINED", null,
                    "tracked assignment batch drained", "SUCCESS");
            listeners.forEach(TaskAssignmentQueueListener::onAssignmentQueueDrained);
        }
    }

    public void stop() {
        running = false;
        trackedTaskIds.clear();
        deferredRequeueTaskIds.clear();
        emitQueueSnapshot(null, null, "STOPPING", null,
                "assignment queue worker is stopping", "SUCCESS");
        if (retryExecutor != null) {
            retryExecutor.shutdownNow();
            try {
                if (!retryExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("TaskAssignWorker retry executor did not terminate within 10 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for TaskAssignWorker retry executor to stop");
            }
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("TaskAssignWorker executor did not terminate within 10 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for TaskAssignWorker to stop");
            }
        }
    }

    private void emitQueueSnapshot(Task task,
                                   TaskStatus taskStatus,
                                   String queueAction,
                                   Long retryDelayMillis,
                                   String reason,
                                   String result) {
        TraceEventLogger.assignmentQueueSnapshot(
                task != null ? task.getTid() : null,
                taskStatus,
                queue.size(),
                pendingTasks.get(),
                scheduledRetryCount.get(),
                queueAction,
                retryDelayMillis,
                queueAction,
                "TaskAssignWorker",
                reason,
                result
        );
    }

    private boolean trackTask(Task task) {
        if (task == null || task.getTid() == null || task.getTid().isBlank()) {
            return true;
        }
        return trackedTaskIds.add(task.getTid());
    }

    private boolean markDeferredRequeue(Task task) {
        if (task == null || task.getTid() == null || task.getTid().isBlank()) {
            return false;
        }
        TaskStatus status = task.getStatus();
        if (status != TaskStatus.READY && status != TaskStatus.RUNNING) {
            return false;
        }
        return deferredRequeueTaskIds.add(task.getTid());
    }

    private boolean enqueueDeferredRequeueIfRequested(Task task) {
        if (task == null || task.getTid() == null || task.getTid().isBlank()) {
            return false;
        }
        String taskId = task.getTid();
        if (!deferredRequeueTaskIds.remove(taskId)) {
            return false;
        }
        TaskStatus status = task.getStatus();
        if (!running || (status != TaskStatus.READY && status != TaskStatus.RUNNING)) {
            releaseTrackedTask(taskId);
            emitQueueSnapshot(task, status, "REQUEUE_DROPPED", null,
                    "deferred requeue was dropped because task is no longer dispatchable", "SKIPPED");
            notifyAssignmentProcessed(task);
            return false;
        }
        queue.offer(task);
        return true;
    }

    private void clearDeferredRequeue(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            deferredRequeueTaskIds.remove(taskId);
        }
    }

    private void releaseTrackedTask(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            trackedTaskIds.remove(taskId);
        }
    }
}
