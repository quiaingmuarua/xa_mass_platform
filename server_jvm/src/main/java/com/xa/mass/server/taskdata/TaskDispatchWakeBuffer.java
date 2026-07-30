package com.xa.mass.server.taskdata;

import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.kernelbinding.TaskDispatchWakeCommands;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public final class TaskDispatchWakeBuffer
        implements TaskDispatchWakeSink, SmartLifecycle {

    private static final System.Logger LOGGER =
            System.getLogger(TaskDispatchWakeBuffer.class.getName());

    private final TaskDispatchWakeCommands commands;
    private final int capacity;
    private final int batchLimit;
    private final Object monitor = new Object();
    private final LinkedHashSet<String> taskIds = new LinkedHashSet<>();
    private volatile boolean running;
    private volatile Thread workerThread;

    public TaskDispatchWakeBuffer(
            TaskDispatchWakeCommands commands,
            TaskRpcProperties properties
    ) {
        this.commands = commands;
        this.capacity = properties.wakeBufferCapacity();
        this.batchLimit = properties.wakeBatchLimit();
    }

    @Override
    public boolean offer(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must be non-blank");
        }
        synchronized (monitor) {
            if (!running) {
                return false;
            }
            if (taskIds.contains(taskId)) {
                return true;
            }
            if (taskIds.size() >= capacity) {
                return false;
            }
            taskIds.add(taskId);
            monitor.notifyAll();
            return true;
        }
    }

    @Override
    public void start() {
        synchronized (monitor) {
            if (running) {
                return;
            }
            running = true;
            workerThread = Thread.ofVirtual()
                    .name("task-dispatch-wake")
                    .start(this::run);
        }
    }

    @Override
    public void stop() {
        Thread thread;
        synchronized (monitor) {
            running = false;
            taskIds.clear();
            thread = workerThread;
            workerThread = null;
            monitor.notifyAll();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        while (running) {
            List<String> batch;
            try {
                batch = takeBatch();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            if (batch.isEmpty()) {
                continue;
            }
            try {
                commands.wakeTaskDispatch(batch);
            } catch (RuntimeException error) {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "{0} operation=taskDispatchWake.send"
                                + " droppedTaskCount={1}",
                        ServerErrorCode.KERNEL_UNAVAILABLE.code(),
                        batch.size()
                );
            }
        }
    }

    private List<String> takeBatch() throws InterruptedException {
        synchronized (monitor) {
            while (running && taskIds.isEmpty()) {
                monitor.wait();
            }
            if (!running) {
                return List.of();
            }
            var batch = new ArrayList<String>(
                    Math.min(batchLimit, taskIds.size())
            );
            var iterator = taskIds.iterator();
            while (iterator.hasNext() && batch.size() < batchLimit) {
                batch.add(iterator.next());
                iterator.remove();
            }
            return batch;
        }
    }
}
