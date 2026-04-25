package com.xa.mass.engine.storage;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * In-memory task storage optimized for frequent task-message writes.
 */
public class InMemoryTaskStorage implements TaskStorage {

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<String, MessageBucket> taskMessages = new ConcurrentHashMap<>();
    private final Map<String, Map<String, AttemptBucket>> taskMessageAttempts = new ConcurrentHashMap<>();

    @Override
    public void saveTask(Task task) {
        tasks.put(task.getTid(), task);
        taskMessages.put(task.getTid(), new MessageBucket());
        taskMessageAttempts.put(task.getTid(), new ConcurrentHashMap<>());
    }

    @Override
    public Optional<Task> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public boolean updateTask(Task task) {
        if (task == null || task.getTid() == null || !tasks.containsKey(task.getTid())) {
            return false;
        }
        tasks.put(task.getTid(), task);
        return true;
    }

    @Override
    public boolean deleteTask(String taskId) {
        Task removed = tasks.remove(taskId);
        taskMessages.remove(taskId);
        taskMessageAttempts.remove(taskId);
        return removed != null;
    }

    @Override
    public List<Task> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    @Override
    public List<Task> getTasksByStatus(TaskStatus status) {
        return tasks.values().stream()
                .filter(task -> task.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public List<Task> getSchedulableTasks() {
        return tasks.values().stream()
                .filter(Task::isSchedulable)
                .collect(Collectors.toList());
    }

    @Override
    public void addTaskMessage(String taskId, TaskMsg taskMsg) {
        MessageBucket bucket = taskMessages.get(taskId);
        if (bucket != null && taskMsg != null && taskMsg.getMessageId() != null) {
            bucket.add(taskMsg);
            taskMessageAttempts.computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>())
                    .putIfAbsent(taskMsg.getMessageId(), new AttemptBucket());
        }
    }

    @Override
    public List<TaskMsg> getTaskMessages(String taskId) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? bucket.snapshot() : List.of();
    }

    @Override
    public List<TaskMsg> getTaskMessagesPage(String taskId, int offset, int limit) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? bucket.snapshotPage(offset, limit) : List.of();
    }

    @Override
    public long countTaskMessages(String taskId) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? bucket.size() : 0;
    }

    @Override
    public int countPendingDispatchableMessages(String taskId) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? bucket.pendingDispatchableCount() : 0;
    }

    @Override
    public boolean hasProcessingMessagesForWorker(String taskId, String workerId) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null && bucket.hasProcessingMessagesForWorker(workerId);
    }

    @Override
    public Optional<TaskMsg> getTaskMessage(String taskId, String messageId) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? bucket.get(messageId) : Optional.empty();
    }

    @Override
    public boolean updateTaskMessage(String taskId, TaskMsg taskMsg) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null && taskMsg != null && taskMsg.getMessageId() != null && bucket.update(taskMsg);
    }

    @Override
    public void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        taskMessageAttempts
                .computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(messageId, ignored -> new AttemptBucket())
                .add(attempt);
    }

    @Override
    public List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId) {
        AttemptBucket bucket = getAttemptBucket(taskId, messageId);
        return bucket != null ? bucket.snapshot() : List.of();
    }

    @Override
    public Optional<TaskMsgAttempt> getLatestTaskMessageAttempt(String taskId, String messageId) {
        AttemptBucket bucket = getAttemptBucket(taskId, messageId);
        return bucket != null ? bucket.latest() : Optional.empty();
    }

    @Override
    public Optional<TaskMsgAttempt> getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        AttemptBucket bucket = getAttemptBucket(taskId, messageId);
        return bucket != null ? bucket.latestActive() : Optional.empty();
    }

    @Override
    public boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        AttemptBucket bucket = getAttemptBucket(taskId, messageId);
        return bucket != null && attempt != null && attempt.getAttemptId() != null && bucket.update(attempt);
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
            for (TaskMsgAttempt attempt : bucket.snapshot()) {
                totalAttempts++;
                if (attempt.getStatus() != null && attempt.getStatus().isActive()) {
                    activeAttempts++;
                }
                if (attempt.getStatus() == TaskMsgAttemptStatus.RUNNING) {
                    runningAttempts++;
                }
                if (attempt.getStatus() == TaskMsgAttemptStatus.FAILED) {
                    failedAttempts++;
                }
                if (attempt.getStatus() == TaskMsgAttemptStatus.EXPIRED) {
                    expiredAttempts++;
                }
            }
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

    private static final class MessageBucket {
        private final Map<String, TaskMsg> messagesById = new ConcurrentHashMap<>();
        private final ConcurrentLinkedDeque<String> orderedMsgIds = new ConcurrentLinkedDeque<>();
        private final Map<String, TaskMsgStatus> statusByMessageId = new ConcurrentHashMap<>();
        private final Map<String, String> latestWorkerByMessageId = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> processingCountsByWorker = new ConcurrentHashMap<>();
        private int initCount;
        private int successCount;
        private int failedCount;
        private int expiredCount;
        private int processingCount;

        private synchronized void add(TaskMsg taskMsg) {
            TaskMsg previous = messagesById.putIfAbsent(taskMsg.getMessageId(), taskMsg);
            if (previous == null) {
                orderedMsgIds.addLast(taskMsg.getMessageId());
                reconcileMessageState(taskMsg.getMessageId(), taskMsg.getStatus(), taskMsg.getLatestAttemptWorkerId());
                return;
            }
            messagesById.put(taskMsg.getMessageId(), taskMsg);
            reconcileMessageState(taskMsg.getMessageId(), taskMsg.getStatus(), taskMsg.getLatestAttemptWorkerId());
        }

        private synchronized Optional<TaskMsg> get(String messageId) {
            return Optional.ofNullable(messagesById.get(messageId));
        }

        private synchronized boolean update(TaskMsg taskMsg) {
            TaskMsg previous = messagesById.get(taskMsg.getMessageId());
            if (previous == null) {
                return false;
            }
            messagesById.put(taskMsg.getMessageId(), taskMsg);
            reconcileMessageState(taskMsg.getMessageId(), taskMsg.getStatus(), taskMsg.getLatestAttemptWorkerId());
            return true;
        }

        private synchronized List<TaskMsg> snapshot() {
            List<TaskMsg> snapshot = new ArrayList<>(messagesById.size());
            for (String messageId : orderedMsgIds) {
                TaskMsg message = messagesById.get(messageId);
                if (message != null) {
                    snapshot.add(message);
                }
            }
            return snapshot;
        }

        private synchronized List<TaskMsg> snapshotPage(int offset, int limit) {
            if (limit <= 0 || messagesById.isEmpty()) {
                return List.of();
            }
            int normalizedOffset = Math.max(0, offset);
            if (normalizedOffset >= orderedMsgIds.size()) {
                return List.of();
            }
            List<TaskMsg> page = new ArrayList<>(Math.min(limit, messagesById.size()));
            int index = 0;
            for (String messageId : orderedMsgIds) {
                if (index++ < normalizedOffset) {
                    continue;
                }
                TaskMsg message = messagesById.get(messageId);
                if (message != null) {
                    page.add(message);
                    if (page.size() >= limit) {
                        break;
                    }
                }
            }
            return page;
        }

        private synchronized int size() {
            return messagesById.size();
        }

        private synchronized int pendingDispatchableCount() {
            return initCount;
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

        private synchronized boolean hasProcessingMessagesForWorker(String workerId) {
            if (workerId == null || workerId.isBlank()) {
                return false;
            }
            AtomicInteger counter = processingCountsByWorker.get(workerId);
            return counter != null && counter.get() > 0;
        }

        private void applyMessageStateDelta(TaskMsgStatus previousStatus,
                                            String previousWorkerId,
                                            TaskMsgStatus newStatus,
                                            String newWorkerId) {
            decrementMessageState(previousStatus, previousWorkerId);
            incrementMessageState(newStatus, newWorkerId);
        }

        private void reconcileMessageState(String messageId,
                                           TaskMsgStatus newStatus,
                                           String newWorkerId) {
            TaskMsgStatus previousStatus = statusByMessageId.get(messageId);
            String previousWorkerId = latestWorkerByMessageId.get(messageId);
            applyMessageStateDelta(previousStatus, previousWorkerId, newStatus, newWorkerId);
            if (newStatus == null) {
                statusByMessageId.remove(messageId);
            } else {
                statusByMessageId.put(messageId, newStatus);
            }
            if (newWorkerId == null || newWorkerId.isBlank()) {
                latestWorkerByMessageId.remove(messageId);
            } else {
                latestWorkerByMessageId.put(messageId, newWorkerId);
            }
        }

        private void decrementMessageState(TaskMsgStatus status, String workerId) {
            if (status == null) {
                return;
            }
            if (status == TaskMsgStatus.INIT) {
                initCount--;
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
                decrementWorkerProcessing(workerId);
            }
        }

        private void incrementMessageState(TaskMsgStatus status, String workerId) {
            if (status == null) {
                return;
            }
            if (status == TaskMsgStatus.INIT) {
                initCount++;
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
                incrementWorkerProcessing(workerId);
            }
        }

        private void incrementWorkerProcessing(String workerId) {
            if (workerId == null || workerId.isBlank()) {
                return;
            }
            processingCountsByWorker.computeIfAbsent(workerId, ignored -> new AtomicInteger()).incrementAndGet();
        }

        private void decrementWorkerProcessing(String workerId) {
            if (workerId == null || workerId.isBlank()) {
                return;
            }
            processingCountsByWorker.computeIfPresent(workerId, (ignored, counter) -> {
                int next = counter.decrementAndGet();
                return next <= 0 ? null : counter;
            });
        }

    }

    private static final class AttemptBucket {
        private final Map<String, TaskMsgAttempt> attemptsById = new ConcurrentHashMap<>();
        private final ConcurrentLinkedDeque<String> orderedAttemptIds = new ConcurrentLinkedDeque<>();
        private final Map<String, TaskMsgAttemptStatus> statusByAttemptId = new ConcurrentHashMap<>();
        private String latestActiveAttemptId;

        private synchronized void add(TaskMsgAttempt attempt) {
            if (attempt == null || attempt.getAttemptId() == null) {
                return;
            }
            TaskMsgAttempt previous = attemptsById.putIfAbsent(attempt.getAttemptId(), attempt);
            if (previous == null) {
                orderedAttemptIds.addLast(attempt.getAttemptId());
            } else {
                attemptsById.put(attempt.getAttemptId(), attempt);
            }
            reconcileActiveAttempt(attempt.getAttemptId(), statusByAttemptId.put(attempt.getAttemptId(), attempt.getStatus()),
                    attempt.getStatus());
        }

        private synchronized boolean update(TaskMsgAttempt attempt) {
            if (attempt == null || attempt.getAttemptId() == null || !attemptsById.containsKey(attempt.getAttemptId())) {
                return false;
            }
            attemptsById.put(attempt.getAttemptId(), attempt);
            reconcileActiveAttempt(attempt.getAttemptId(), statusByAttemptId.put(attempt.getAttemptId(), attempt.getStatus()),
                    attempt.getStatus());
            return true;
        }

        private synchronized List<TaskMsgAttempt> snapshot() {
            List<TaskMsgAttempt> snapshot = new ArrayList<>(attemptsById.size());
            for (String attemptId : orderedAttemptIds) {
                TaskMsgAttempt attempt = attemptsById.get(attemptId);
                if (attempt != null) {
                    snapshot.add(attempt);
                }
            }
            return snapshot;
        }

        private synchronized Optional<TaskMsgAttempt> latest() {
            String latestAttemptId = orderedAttemptIds.peekLast();
            if (latestAttemptId == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(attemptsById.get(latestAttemptId));
        }

        private synchronized Optional<TaskMsgAttempt> latestActive() {
            if (latestActiveAttemptId == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(attemptsById.get(latestActiveAttemptId));
        }

        private void reconcileActiveAttempt(String attemptId,
                                            TaskMsgAttemptStatus previousStatus,
                                            TaskMsgAttemptStatus currentStatus) {
            if (currentStatus != null && currentStatus.isActive()) {
                latestActiveAttemptId = attemptId;
                return;
            }
            if (attemptId != null && attemptId.equals(latestActiveAttemptId)
                    && (currentStatus == null || !currentStatus.isActive())) {
                latestActiveAttemptId = findLatestActiveAttemptId();
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
