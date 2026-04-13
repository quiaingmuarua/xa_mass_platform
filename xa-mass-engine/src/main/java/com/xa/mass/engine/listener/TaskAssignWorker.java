package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class TaskAssignWorker {
    private static final Logger log = LoggerFactory.getLogger(TaskAssignWorker.class);
    private static final long DEFAULT_RETRY_DELAY_MILLIS = 1000L;
    private final TaskDeviceAssignListener deviceAssignListener;
    private final long retryDelayMillis;
    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    private final List<TaskCompletionListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private volatile boolean running = true;
    private ExecutorService executor;
    private ScheduledExecutorService retryExecutor;

    public TaskAssignWorker(TaskDeviceAssignListener deviceAssignListener) {
        this(deviceAssignListener, DEFAULT_RETRY_DELAY_MILLIS);
    }

    public TaskAssignWorker(TaskDeviceAssignListener deviceAssignListener, long retryDelayMillis) {
        this.deviceAssignListener = deviceAssignListener;
        this.retryDelayMillis = retryDelayMillis;
    }

    public void addListener(TaskCompletionListener listener) {
        listeners.add(listener);
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
                    if (task.getStatus() == TaskStatus.READY) {
                        deviceAssignListener.onTaskAssign(task);
                        if (running && task.getStatus() == TaskStatus.READY) {
                            scheduleRetry(task);
                        }
                        notifyTaskCompleted(task);
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

        return CompletableFuture.runAsync(() -> {
            while (pendingTasks.get() > 0 && running) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, executor);
    }

    public void submit(Task task) {
        queue.offer(task);
    }

    private void scheduleRetry(Task task) {
        if (retryExecutor == null) {
            return;
        }
        retryExecutor.schedule(() -> {
            if (running && task.getStatus() == TaskStatus.READY) {
                queue.offer(task);
            }
        }, retryDelayMillis, TimeUnit.MILLISECONDS);
    }

    private void notifyTaskCompleted(Task task) {
        int previous = pendingTasks.getAndUpdate(current -> current > 0 ? current - 1 : 0);
        int remaining = previous > 0 ? previous - 1 : 0;
        listeners.forEach(l -> l.onTaskCompleted(task));

        if (previous > 0 && remaining == 0) {
            listeners.forEach(TaskCompletionListener::onAllTasksCompleted);
        }
    }

    public void stop() {
        running = false;
        // Interrupt the blocking queue.take() so the worker thread exits promptly
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
