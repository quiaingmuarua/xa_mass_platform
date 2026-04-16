package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.util.TraceEventLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
                try {
                    Task task = queue.take();
                    TaskStatus initialStatus = task.getStatus();
                    if (initialStatus == TaskStatus.READY || initialStatus == TaskStatus.RUNNING) {
                        boolean assigned = workerAssignListener.onTaskAssign(task);
                        if (running && !assigned && task.getStatus() == initialStatus) {
                            scheduleRetry(task, initialStatus);
                        } else {
                            notifyAssignmentProcessed(task);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("TaskAssignWorker error: {}", e.getMessage(), e);
                }
            }
        });
    }

    public CompletableFuture<Void> submitAll(List<Task> tasks) {
        pendingTasks.set(tasks.size());
        tasks.forEach(this::submit);

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

    public void submit(Task task) {
        queue.offer(task);
    }

    private void scheduleRetry(Task task, TaskStatus expectedStatus) {
        if (retryExecutor == null) {
            return;
        }
        TraceEventLogger.assignmentRetryScheduled(
                task.getTid(),
                expectedStatus,
                "NO_ASSIGNMENT_RESULT",
                "TaskAssignWorker",
                "task remained eligible after assignment attempt",
                retryDelayMillis
        );
        retryExecutor.schedule(() -> {
            if (running && task.getStatus() == expectedStatus) {
                queue.offer(task);
            }
        }, retryDelayMillis, TimeUnit.MILLISECONDS);
    }

    private void notifyAssignmentProcessed(Task task) {
        int previous = pendingTasks.getAndUpdate(current -> current > 0 ? current - 1 : 0);
        int remaining = previous > 0 ? previous - 1 : 0;
        listeners.forEach(l -> l.onTaskAssignmentProcessed(task));

        if (previous > 0 && remaining == 0) {
            listeners.forEach(TaskAssignmentQueueListener::onAssignmentQueueDrained);
        }
    }

    public void stop() {
        running = false;
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
}
