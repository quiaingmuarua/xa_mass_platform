package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class TaskAssignWorker {
    private static final Logger log = LoggerFactory.getLogger(TaskAssignWorker.class);
    private final TaskDeviceAssignListener deviceAssignListener;
    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    private final List<TaskCompletionListener> listeners = new ArrayList<>();
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private volatile boolean running = true;
    private ExecutorService executor;

    public TaskAssignWorker(TaskDeviceAssignListener deviceAssignListener) {
        this.deviceAssignListener = deviceAssignListener;
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

        executor.submit(() -> {
            while (running) {
                try {
                    Task task = queue.take();
                    if (task.getStatus() == TaskStatus.READY) {
                        deviceAssignListener.onTaskAssign(task);
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

    private void notifyTaskCompleted(Task task) {
        int remaining = pendingTasks.decrementAndGet();
        listeners.forEach(l -> l.onTaskCompleted(task));

        if (remaining == 0) {
            listeners.forEach(TaskCompletionListener::onAllTasksCompleted);
        }
    }

    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdown();
        }
    }
} 