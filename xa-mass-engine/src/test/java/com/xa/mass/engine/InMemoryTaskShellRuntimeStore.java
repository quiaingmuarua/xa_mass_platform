package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.kernel.spi.task.TaskShellRuntimeLifecycleQuery;
import com.xa.mass.kernel.spi.task.TaskShellRuntimeStore;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Engine test fixture for the runtime task-shell SPI.
 */
public final class InMemoryTaskShellRuntimeStore
        implements TaskShellRuntimeStore, TaskShellRuntimeLifecycleQuery {

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> maxRuntimeDeadlineByTask = new ConcurrentHashMap<>();
    private final PriorityQueue<TaskRuntimeDeadline> maxRuntimeDeadlineIndex = new PriorityQueue<>(
            Comparator.comparing(TaskRuntimeDeadline::deadline).thenComparing(TaskRuntimeDeadline::taskId)
    );

    @Override
    public synchronized void saveTask(Task task) {
        if (task == null || task.getTid() == null) {
            return;
        }
        tasks.put(task.getTid(), task);
        updateMaxRuntimeDeadline(task);
    }

    @Override
    public Optional<Task> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public synchronized boolean updateTask(Task task) {
        if (task == null || task.getTid() == null || !tasks.containsKey(task.getTid())) {
            return false;
        }
        tasks.put(task.getTid(), task);
        updateMaxRuntimeDeadline(task);
        return true;
    }

    @Override
    public synchronized boolean deleteTask(String taskId) {
        Task removed = tasks.remove(taskId);
        clearMaxRuntimeDeadline(taskId);
        return removed != null;
    }

    @Override
    public List<Task> pollTasksPastMaxRuntimeDeadline(LocalDateTime now, int limit) {
        if (now == null || limit <= 0) {
            return List.of();
        }
        List<Task> expired = new ArrayList<>(Math.min(limit, 16));
        synchronized (maxRuntimeDeadlineIndex) {
            while (expired.size() < limit && !maxRuntimeDeadlineIndex.isEmpty()) {
                TaskRuntimeDeadline next = maxRuntimeDeadlineIndex.peek();
                if (!next.deadline().isBefore(now)) {
                    break;
                }
                maxRuntimeDeadlineIndex.poll();
                LocalDateTime currentDeadline = maxRuntimeDeadlineByTask.get(next.taskId());
                if (!next.deadline().equals(currentDeadline)) {
                    continue;
                }
                Task task = tasks.get(next.taskId());
                LocalDateTime recomputedDeadline = maxRuntimeDeadline(task);
                if (recomputedDeadline == null) {
                    maxRuntimeDeadlineByTask.remove(next.taskId());
                    continue;
                }
                if (!next.deadline().equals(recomputedDeadline)) {
                    maxRuntimeDeadlineByTask.put(next.taskId(), recomputedDeadline);
                    maxRuntimeDeadlineIndex.offer(new TaskRuntimeDeadline(next.taskId(), recomputedDeadline));
                    continue;
                }
                maxRuntimeDeadlineByTask.remove(next.taskId());
                expired.add(task);
            }
        }
        return expired;
    }

    private void updateMaxRuntimeDeadline(Task task) {
        synchronized (maxRuntimeDeadlineIndex) {
            LocalDateTime deadline = maxRuntimeDeadline(task);
            if (deadline == null) {
                maxRuntimeDeadlineByTask.remove(task.getTid());
                return;
            }
            maxRuntimeDeadlineByTask.put(task.getTid(), deadline);
            maxRuntimeDeadlineIndex.offer(new TaskRuntimeDeadline(task.getTid(), deadline));
        }
    }

    private void clearMaxRuntimeDeadline(String taskId) {
        if (taskId == null) {
            return;
        }
        synchronized (maxRuntimeDeadlineIndex) {
            maxRuntimeDeadlineByTask.remove(taskId);
        }
    }

    private LocalDateTime maxRuntimeDeadline(Task task) {
        if (task == null
                || task.getStatus() == null
                || task.getStatus().isFinal()
                || task.getExecutionSpec().getMaxRuntimeSeconds() <= 0
                || task.getStartTime() == null) {
            return null;
        }
        return task.getStartTime().plusSeconds(task.getExecutionSpec().getMaxRuntimeSeconds());
    }

    private record TaskRuntimeDeadline(String taskId, LocalDateTime deadline) {
    }
}
