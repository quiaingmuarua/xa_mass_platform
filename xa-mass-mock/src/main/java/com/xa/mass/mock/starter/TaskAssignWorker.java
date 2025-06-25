package com.xa.mass.mock.starter;

import com.xa.mass.engine.model.task.Task;
import com.xa.mass.engine.assign.TaskDeviceAssignListener;
import com.xa.mass.engine.model.enums.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class TaskAssignWorker {
    private static final Logger log = LoggerFactory.getLogger(TaskAssignWorker.class);
    private final TaskDeviceAssignListener deviceAssignListener;
    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    private volatile boolean running = true;
    private Thread workerThread;

    public TaskAssignWorker(TaskDeviceAssignListener deviceAssignListener) {
        this.deviceAssignListener = deviceAssignListener;
    }

    public void start() {
        workerThread = new Thread(() -> {
            while (running) {
                try {
                    Task task = queue.take();
                    if (task.getStatus() == TaskStatus.READY) {
                        deviceAssignListener.onTaskAssign(task);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("TaskAssignWorker error: {}", e.getMessage(), e);
                }
            }
        }, "TaskAssignWorker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public void submit(Task task) {
        queue.offer(task);
    }

    public void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }
} 