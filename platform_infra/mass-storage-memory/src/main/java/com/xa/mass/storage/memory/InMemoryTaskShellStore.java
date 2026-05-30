package com.xa.mass.storage.memory;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.storage.api.TaskShellLifecycleQuery;
import com.xa.mass.storage.api.TaskShellStore;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory task storage optimized for frequent task-message writes.
 */
public class InMemoryTaskShellStore implements TaskShellStore, TaskShellLifecycleQuery {

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<TaskStatus, java.util.LinkedHashSet<String>> taskIdsByStatus = new ConcurrentHashMap<>();
    private final Map<String, java.util.LinkedHashSet<String>> taskIdsByProject = new ConcurrentHashMap<>();
    private final Map<String, TaskStatus> indexedStatusByTask = new ConcurrentHashMap<>();
    private final Map<String, String> indexedProjectByTask = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> maxRuntimeDeadlineByTask = new ConcurrentHashMap<>();
    private final PriorityQueue<TaskRuntimeDeadline> maxRuntimeDeadlineIndex = new PriorityQueue<>(
            Comparator.comparing(TaskRuntimeDeadline::deadline).thenComparing(TaskRuntimeDeadline::taskId)
    );

    @Override
    public synchronized void saveTask(Task task) {
        Task previous = tasks.put(task.getTid(), task);
        removeTaskIndexes(previous);
        addTaskIndexes(task);
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
        Task previous = tasks.put(task.getTid(), task);
        removeTaskIndexes(previous);
        addTaskIndexes(task);
        updateMaxRuntimeDeadline(task);
        return true;
    }

    @Override
    public synchronized boolean deleteTask(String taskId) {
        Task removed = tasks.remove(taskId);
        removeTaskIndexes(removed);
        clearMaxRuntimeDeadline(taskId);
        return removed != null;
    }

    @Override
    public List<Task> listTasksPaged(int offset, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return tasks.values().stream()
                .skip(Math.max(0, offset))
                .limit(limit)
                .toList();
    }

    @Override
    public List<Task> getTasksByStatus(TaskStatus status) {
        synchronized (this) {
            return tasksByIds(taskIdsByStatus.get(status));
        }
    }

    @Override
    public List<Task> getTasksByProject(String project) {
        synchronized (this) {
            String normalizedProject = normalize(project);
            return normalizedProject == null
                    ? List.of()
                    : tasksByIds(taskIdsByProject.get(normalizedProject));
        }
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
        if (task == null || task.getTid() == null) {
            return;
        }
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

    private void addTaskIndexes(Task task) {
        if (task == null || task.getTid() == null) {
            return;
        }
        TaskStatus status = task.getStatus();
        if (status != null) {
            taskIdsByStatus.computeIfAbsent(status, ignored -> new java.util.LinkedHashSet<>())
                    .add(task.getTid());
            indexedStatusByTask.put(task.getTid(), status);
        } else {
            indexedStatusByTask.remove(task.getTid());
        }
        String project = normalize(task.getProject());
        if (project != null) {
            taskIdsByProject.computeIfAbsent(project, ignored -> new java.util.LinkedHashSet<>())
                    .add(task.getTid());
            indexedProjectByTask.put(task.getTid(), project);
        } else {
            indexedProjectByTask.remove(task.getTid());
        }
    }

    private void removeTaskIndexes(Task task) {
        if (task == null || task.getTid() == null) {
            return;
        }
        TaskStatus indexedStatus = indexedStatusByTask.remove(task.getTid());
        if (indexedStatus != null) {
            removeTaskIndex(taskIdsByStatus, indexedStatus, task.getTid());
        }
        String indexedProject = indexedProjectByTask.remove(task.getTid());
        if (indexedProject != null) {
            removeTaskIndex(taskIdsByProject, indexedProject, task.getTid());
        }
    }

    private <K> void removeTaskIndex(Map<K, java.util.LinkedHashSet<String>> index, K key, String taskId) {
        java.util.LinkedHashSet<String> taskIds = index.get(key);
        if (taskIds == null) {
            return;
        }
        taskIds.remove(taskId);
        if (taskIds.isEmpty()) {
            index.remove(key);
        }
    }

    private List<Task> tasksByIds(java.util.Collection<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }
        List<Task> result = new ArrayList<>(taskIds.size());
        for (String taskId : taskIds) {
            Task task = tasks.get(taskId);
            if (task != null) {
                result.add(task);
            }
        }
        return result;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record TaskRuntimeDeadline(String taskId, LocalDateTime deadline) {
    }

}
