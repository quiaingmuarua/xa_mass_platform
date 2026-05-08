package com.xa.mass.storage.memory;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.TaskStorage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * In-memory task storage optimized for frequent task-message writes.
 */
public class InMemoryTaskStorage implements TaskStorage, TaskDetailStore {

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<TaskStatus, java.util.LinkedHashSet<String>> taskIdsByStatus = new ConcurrentHashMap<>();
    private final Map<String, java.util.LinkedHashSet<String>> taskIdsByProject = new ConcurrentHashMap<>();
    private final java.util.LinkedHashSet<String> schedulableTaskIds = new java.util.LinkedHashSet<>();
    private final Map<String, TaskStatus> indexedStatusByTask = new ConcurrentHashMap<>();
    private final Map<String, String> indexedProjectByTask = new ConcurrentHashMap<>();
    private final Map<String, Boolean> indexedSchedulableByTask = new ConcurrentHashMap<>();
    private final Map<String, MessageBucket> taskMessages = new ConcurrentHashMap<>();
    private final Map<String, Map<String, AttemptBucket>> taskMessageAttempts = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> maxRuntimeDeadlineByTask = new ConcurrentHashMap<>();
    private final PriorityQueue<TaskRuntimeDeadline> maxRuntimeDeadlineIndex = new PriorityQueue<>(
            Comparator.comparing(TaskRuntimeDeadline::deadline).thenComparing(TaskRuntimeDeadline::taskId)
    );

    @Override
    public synchronized void saveTask(Task task) {
        Task previous = tasks.put(task.getTid(), task);
        removeTaskIndexes(previous);
        addTaskIndexes(task);
        taskMessages.computeIfAbsent(task.getTid(), ignored -> new MessageBucket());
        taskMessageAttempts.computeIfAbsent(task.getTid(), ignored -> new ConcurrentHashMap<>());
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
        taskMessages.remove(taskId);
        taskMessageAttempts.remove(taskId);
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
    public List<Task> getSchedulableTasks() {
        synchronized (this) {
            return tasksByIds(schedulableTaskIds);
        }
    }

    @Override
    public List<Task> pollExpiredMaxRuntimeTasks(LocalDateTime now, int limit) {
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

    @Override
    public boolean upsertTaskMessageProjection(String taskId, TaskDetailStore.TaskMessageProjection projection) {
        MessageBucket bucket = taskMessages.get(taskId);
        if (bucket != null && projection != null && projection.messageId() != null) {
            bucket.add(projection);
            taskMessageAttempts.computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>())
                    .putIfAbsent(projection.messageId(), new AttemptBucket());
            return true;
        }
        return false;
    }

    @Override
    public List<TaskDetailStore.TaskMessageProjection> getTaskMessageProjections(String taskId) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? bucket.snapshot() : List.of();
    }

    @Override
    public List<TaskDetailStore.TaskMessageProjection> getTaskMessageProjections(String taskId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? bucket.snapshot(limit) : List.of();
    }

    @Override
    public Optional<TaskDetailStore.TaskMessageProjection> getTaskMessageProjection(String taskId, String messageId) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? bucket.get(messageId) : Optional.empty();
    }

    @Override
    public boolean upsertTaskMessageAttemptProjection(String taskId,
                                                      String messageId,
                                                      TaskDetailStore.TaskMessageAttemptProjection projection) {
        if (projection == null || projection.attemptId() == null) {
            return false;
        }
        taskMessageAttempts
                .computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(messageId, ignored -> new AttemptBucket())
                .add(projection);
        return true;
    }

    @Override
    public List<TaskDetailStore.TaskMessageAttemptProjection> getTaskMessageAttemptProjections(String taskId,
                                                                                               String messageId) {
        AttemptBucket bucket = getAttemptBucket(taskId, messageId);
        return bucket != null ? bucket.snapshot() : List.of();
    }

    @Override
    public Optional<TaskDetailStore.TaskMessageAttemptProjection> getLatestTaskMessageAttemptProjection(String taskId,
                                                                                                         String messageId) {
        AttemptBucket bucket = getAttemptBucket(taskId, messageId);
        return bucket != null ? bucket.latest() : Optional.empty();
    }

    @Override
    public TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId) {
        AttemptBucket bucket = getAttemptBucket(taskId, messageId);
        return bucket != null ? bucket.stats() : new TaskMessageAttemptStats(0, 0, 0, 0, 0);
    }

    @Override
    public TaskMessageStats getTaskMessageStats(String taskId) {
        MessageBucket bucket = taskMessages.get(taskId);
        if (bucket == null) {
            return new TaskMessageStats(0, 0, 0, 0, 0);
        }

        long total = bucket.size();
        long success = bucket.successCount();
        long failed = bucket.failedCount();
        long expired = bucket.expiredCount();
        long processing = bucket.processingCount();

        return new TaskMessageStats(total, success, failed, expired, processing);
    }

    @Override
    public TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId) {
        Map<String, AttemptBucket> attemptsByMsg = taskMessageAttempts.get(taskId);
        if (attemptsByMsg == null) {
            return new TaskMessageAttemptStats(0, 0, 0, 0, 0);
        }

        long totalAttempts = 0;
        long activeAttempts = 0;
        long runningAttempts = 0;
        long failedAttempts = 0;
        long expiredAttempts = 0;

        for (AttemptBucket bucket : attemptsByMsg.values()) {
            TaskMessageAttemptStats stats = bucket.stats();
            totalAttempts += stats.getTotalAttempts();
            activeAttempts += stats.getActiveAttempts();
            runningAttempts += stats.getRunningAttempts();
            failedAttempts += stats.getFailedAttempts();
            expiredAttempts += stats.getExpiredAttempts();
        }

        return new TaskMessageAttemptStats(totalAttempts, activeAttempts, runningAttempts, failedAttempts, expiredAttempts);
    }

    private AttemptBucket getAttemptBucket(String taskId, String messageId) {
        Map<String, AttemptBucket> attemptsByMsg = taskMessageAttempts.get(taskId);
        if (attemptsByMsg == null) {
            return null;
        }
        return attemptsByMsg.get(messageId);
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
                || task.getMaxRuntimeSeconds() <= 0
                || task.getStartTime() == null) {
            return null;
        }
        return task.getStartTime().plusSeconds(task.getMaxRuntimeSeconds());
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
        if (task.isSchedulable()) {
            schedulableTaskIds.add(task.getTid());
            indexedSchedulableByTask.put(task.getTid(), Boolean.TRUE);
        } else {
            schedulableTaskIds.remove(task.getTid());
            indexedSchedulableByTask.put(task.getTid(), Boolean.FALSE);
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
        Boolean indexedSchedulable = indexedSchedulableByTask.remove(task.getTid());
        if (Boolean.TRUE.equals(indexedSchedulable)) {
            schedulableTaskIds.remove(task.getTid());
        } else {
            schedulableTaskIds.remove(task.getTid());
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

    private static final class MessageBucket {
        private final Map<String, TaskDetailStore.TaskMessageProjection> messagesById = new ConcurrentHashMap<>();
        private final ConcurrentLinkedDeque<String> orderedMsgIds = new ConcurrentLinkedDeque<>();
        private final java.util.HashSet<String> nonFinalMessageIds = new java.util.HashSet<>();
        private final Map<String, TaskMsgStatus> statusByMessageId = new ConcurrentHashMap<>();
        private int successCount;
        private int failedCount;
        private int expiredCount;
        private int processingCount;

        private synchronized void add(TaskDetailStore.TaskMessageProjection taskMsg) {
            TaskDetailStore.TaskMessageProjection previous = messagesById.putIfAbsent(taskMsg.messageId(), taskMsg);
            if (previous == null) {
                orderedMsgIds.addLast(taskMsg.messageId());
                reconcileMessageState(taskMsg.messageId(), taskMsg.status());
                return;
            }
            messagesById.put(taskMsg.messageId(), taskMsg);
            reconcileMessageState(taskMsg.messageId(), taskMsg.status());
        }

        private synchronized Optional<TaskDetailStore.TaskMessageProjection> get(String messageId) {
            return Optional.ofNullable(messagesById.get(messageId));
        }

        private synchronized boolean update(TaskDetailStore.TaskMessageProjection taskMsg) {
            TaskDetailStore.TaskMessageProjection previous = messagesById.get(taskMsg.messageId());
            if (previous == null) {
                return false;
            }
            messagesById.put(taskMsg.messageId(), taskMsg);
            reconcileMessageState(taskMsg.messageId(), taskMsg.status());
            return true;
        }

        private synchronized List<TaskDetailStore.TaskMessageProjection> snapshot() {
            List<TaskDetailStore.TaskMessageProjection> snapshot = new ArrayList<>(messagesById.size());
            for (String messageId : orderedMsgIds) {
                TaskDetailStore.TaskMessageProjection message = messagesById.get(messageId);
                if (message != null) {
                    snapshot.add(message);
                }
            }
            return snapshot;
        }

        private synchronized List<TaskDetailStore.TaskMessageProjection> snapshot(int limit) {
            List<TaskDetailStore.TaskMessageProjection> snapshot = new ArrayList<>(Math.min(messagesById.size(), limit));
            for (String messageId : orderedMsgIds) {
                if (snapshot.size() >= limit) {
                    break;
                }
                TaskDetailStore.TaskMessageProjection message = messagesById.get(messageId);
                if (message != null) {
                    snapshot.add(message);
                }
            }
            return snapshot;
        }

        private synchronized List<TaskDetailStore.TaskMessageProjection> snapshotNonFinal() {
            List<TaskDetailStore.TaskMessageProjection> snapshot = new ArrayList<>(nonFinalMessageIds.size());
            for (String messageId : nonFinalMessageIds) {
                TaskDetailStore.TaskMessageProjection message = messagesById.get(messageId);
                if (message != null
                        && message.status() != null
                        && !message.status().isFinal()) {
                    snapshot.add(message);
                }
            }
            return snapshot;
        }

        private synchronized int size() {
            return messagesById.size();
        }

        private synchronized long successCount() {
            return successCount;
        }

        private synchronized long failedCount() {
            return failedCount;
        }

        private synchronized long expiredCount() {
            return expiredCount;
        }

        private synchronized long processingCount() {
            return processingCount;
        }

        private void reconcileMessageState(String messageId,
                                           TaskMsgStatus newStatus) {
            TaskMsgStatus previousStatus = statusByMessageId.get(messageId);
            decrementMessageState(previousStatus);
            incrementMessageState(newStatus);
            if (newStatus == null) {
                statusByMessageId.remove(messageId);
            } else {
                statusByMessageId.put(messageId, newStatus);
            }
            if (newStatus != null && !newStatus.isFinal()) {
                nonFinalMessageIds.add(messageId);
            } else {
                nonFinalMessageIds.remove(messageId);
            }
        }

        private void decrementMessageState(TaskMsgStatus status) {
            if (status == null) {
                return;
            }
            if (status == TaskMsgStatus.SUCCESS) {
                successCount--;
            }
            if (status == TaskMsgStatus.FAILED) {
                failedCount--;
            }
            if (status == TaskMsgStatus.EXPIRED) {
                expiredCount--;
            }
            if (status.isProcessing()) {
                processingCount--;
            }
        }

        private void incrementMessageState(TaskMsgStatus status) {
            if (status == null) {
                return;
            }
            if (status == TaskMsgStatus.SUCCESS) {
                successCount++;
            }
            if (status == TaskMsgStatus.FAILED) {
                failedCount++;
            }
            if (status == TaskMsgStatus.EXPIRED) {
                expiredCount++;
            }
            if (status.isProcessing()) {
                processingCount++;
            }
        }
    }

    private static final class AttemptBucket {
        private final Map<String, TaskDetailStore.TaskMessageAttemptProjection> attemptsById = new ConcurrentHashMap<>();
        private final ConcurrentLinkedDeque<String> orderedAttemptIds = new ConcurrentLinkedDeque<>();
        private final Map<String, TaskMsgAttemptStatus> statusByAttemptId = new ConcurrentHashMap<>();
        private String latestActiveAttemptId;
        private int activeAttemptCount;
        private int runningAttemptCount;
        private int failedAttemptCount;
        private int expiredAttemptCount;

        private synchronized void add(TaskDetailStore.TaskMessageAttemptProjection attempt) {
            if (attempt == null || attempt.attemptId() == null) {
                return;
            }
            TaskDetailStore.TaskMessageAttemptProjection previous = attemptsById.putIfAbsent(attempt.attemptId(), attempt);
            if (previous == null) {
                orderedAttemptIds.addLast(attempt.attemptId());
            } else {
                attemptsById.put(attempt.attemptId(), attempt);
            }
            reconcileAttemptState(
                    attempt.attemptId(),
                    statusByAttemptId.put(attempt.attemptId(), attempt.status()),
                    attempt.status()
            );
        }

        private synchronized boolean update(TaskDetailStore.TaskMessageAttemptProjection attempt) {
            if (attempt == null || attempt.attemptId() == null || !attemptsById.containsKey(attempt.attemptId())) {
                return false;
            }
            attemptsById.put(attempt.attemptId(), attempt);
            reconcileAttemptState(
                    attempt.attemptId(),
                    statusByAttemptId.put(attempt.attemptId(), attempt.status()),
                    attempt.status()
            );
            return true;
        }

        private synchronized List<TaskDetailStore.TaskMessageAttemptProjection> snapshot() {
            List<TaskDetailStore.TaskMessageAttemptProjection> snapshot = new ArrayList<>(attemptsById.size());
            for (String attemptId : orderedAttemptIds) {
                TaskDetailStore.TaskMessageAttemptProjection attempt = attemptsById.get(attemptId);
                if (attempt != null) {
                    snapshot.add(attempt);
                }
            }
            return snapshot;
        }

        private synchronized Optional<TaskDetailStore.TaskMessageAttemptProjection> latest() {
            String latestAttemptId = orderedAttemptIds.peekLast();
            if (latestAttemptId == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(attemptsById.get(latestAttemptId));
        }

        private synchronized Optional<TaskDetailStore.TaskMessageAttemptProjection> latestActive() {
            if (latestActiveAttemptId == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(attemptsById.get(latestActiveAttemptId));
        }

        private synchronized TaskMessageAttemptStats stats() {
            return new TaskMessageAttemptStats(
                    attemptsById.size(),
                    activeAttemptCount,
                    runningAttemptCount,
                    failedAttemptCount,
                    expiredAttemptCount
            );
        }

        private void reconcileAttemptState(String attemptId,
                                           TaskMsgAttemptStatus previousStatus,
                                           TaskMsgAttemptStatus currentStatus) {
            decrementStatusCounts(previousStatus);
            incrementStatusCounts(currentStatus);
            if (currentStatus != null && currentStatus.isActive()) {
                latestActiveAttemptId = attemptId;
                return;
            }
            if (attemptId != null && attemptId.equals(latestActiveAttemptId)
                    && (currentStatus == null || !currentStatus.isActive())) {
                latestActiveAttemptId = findLatestActiveAttemptId();
            }
        }

        private void decrementStatusCounts(TaskMsgAttemptStatus status) {
            if (status == null) {
                return;
            }
            if (status.isActive()) {
                activeAttemptCount--;
            }
            if (status == TaskMsgAttemptStatus.RUNNING) {
                runningAttemptCount--;
            }
            if (status == TaskMsgAttemptStatus.FAILED) {
                failedAttemptCount--;
            }
            if (status == TaskMsgAttemptStatus.EXPIRED) {
                expiredAttemptCount--;
            }
        }

        private void incrementStatusCounts(TaskMsgAttemptStatus status) {
            if (status == null) {
                return;
            }
            if (status.isActive()) {
                activeAttemptCount++;
            }
            if (status == TaskMsgAttemptStatus.RUNNING) {
                runningAttemptCount++;
            }
            if (status == TaskMsgAttemptStatus.FAILED) {
                failedAttemptCount++;
            }
            if (status == TaskMsgAttemptStatus.EXPIRED) {
                expiredAttemptCount++;
            }
        }

        private String findLatestActiveAttemptId() {
            java.util.Iterator<String> iterator = orderedAttemptIds.descendingIterator();
            while (iterator.hasNext()) {
                String attemptId = iterator.next();
                TaskMsgAttemptStatus status = statusByAttemptId.get(attemptId);
                if (status != null && status.isActive()) {
                    return attemptId;
                }
            }
            return null;
        }
    }
}
