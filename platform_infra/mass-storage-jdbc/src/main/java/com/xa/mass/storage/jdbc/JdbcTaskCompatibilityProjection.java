package com.xa.mass.storage.jdbc;

import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.TaskStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Process-local compatibility projection used by JDBC task storage.
 *
 * <p>This keeps bounded task-message and attempt detail in memory without
 * pulling in the full in-memory task-storage implementation. JDBC task truth
 * remains the durable source of record for task shells.</p>
 */
final class JdbcTaskCompatibilityProjection {

    private final Map<String, MessageBucket> taskMessages = new ConcurrentHashMap<>();
    private final Map<String, Map<String, AttemptBucket>> taskMessageAttempts = new ConcurrentHashMap<>();

    void ensureTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        taskMessages.computeIfAbsent(taskId, ignored -> new MessageBucket());
        taskMessageAttempts.computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>());
    }

    void deleteTask(String taskId) {
        if (taskId == null) {
            return;
        }
        taskMessages.remove(taskId);
        taskMessageAttempts.remove(taskId);
    }

    void addTaskMessage(String taskId, TaskMsg taskMsg) {
        MessageBucket bucket = taskMessages.get(taskId);
        if (bucket != null && taskMsg != null && taskMsg.getMessageId() != null) {
            bucket.add(taskMsg);
            taskMessageAttempts.computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>())
                    .putIfAbsent(taskMsg.getMessageId(), new AttemptBucket());
        }
    }

    List<TaskMsg> getTaskMessages(String taskId) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? bucket.snapshot() : List.of();
    }

    List<TaskMsg> getTaskMessages(String taskId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? bucket.snapshot(limit) : List.of();
    }

    List<TaskMsg> getNonFinalTaskMessages(String taskId) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? bucket.snapshotNonFinal() : List.of();
    }

    long countTaskMessages(String taskId) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? bucket.size() : 0;
    }

    Optional<TaskMsg> getTaskMessage(String taskId, String messageId) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? bucket.get(messageId) : Optional.empty();
    }

    boolean updateTaskMessage(String taskId, TaskMsg taskMsg) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null && taskMsg != null && taskMsg.getMessageId() != null && bucket.update(taskMsg);
    }

    void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        taskMessageAttempts
                .computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(messageId, ignored -> new AttemptBucket())
                .add(attempt);
    }

    List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId) {
        AttemptBucket bucket = getAttemptBucket(taskId, messageId);
        return bucket != null ? bucket.snapshot() : List.of();
    }

    Optional<TaskMsgAttempt> getLatestTaskMessageAttempt(String taskId, String messageId) {
        AttemptBucket bucket = getAttemptBucket(taskId, messageId);
        return bucket != null ? bucket.latest() : Optional.empty();
    }

    Optional<TaskMsgAttempt> getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        AttemptBucket bucket = getAttemptBucket(taskId, messageId);
        return bucket != null ? bucket.latestActive() : Optional.empty();
    }

    TaskDetailStore.TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId) {
        AttemptBucket bucket = getAttemptBucket(taskId, messageId);
        return bucket != null ? bucket.stats() : new TaskDetailStore.TaskMessageAttemptStats(0, 0, 0, 0, 0);
    }

    boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        AttemptBucket bucket = getAttemptBucket(taskId, messageId);
        return bucket != null && attempt != null && attempt.getAttemptId() != null && bucket.update(attempt);
    }

    TaskDetailStore.TaskMessageStats getTaskMessageStats(String taskId) {
        MessageBucket bucket = taskMessages.get(taskId);
        if (bucket == null) {
            return new TaskDetailStore.TaskMessageStats(0, 0, 0, 0, 0);
        }
        return new TaskDetailStore.TaskMessageStats(
                bucket.size(),
                bucket.successCount(),
                bucket.failedCount(),
                bucket.expiredCount(),
                bucket.processingCount()
        );
    }

    TaskDetailStore.TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId) {
        Map<String, AttemptBucket> attemptsByMsg = taskMessageAttempts.get(taskId);
        if (attemptsByMsg == null) {
            return new TaskDetailStore.TaskMessageAttemptStats(0, 0, 0, 0, 0);
        }

        long totalAttempts = 0;
        long activeAttempts = 0;
        long runningAttempts = 0;
        long failedAttempts = 0;
        long expiredAttempts = 0;

        for (AttemptBucket bucket : attemptsByMsg.values()) {
            TaskDetailStore.TaskMessageAttemptStats stats = bucket.stats();
            totalAttempts += stats.getTotalAttempts();
            activeAttempts += stats.getActiveAttempts();
            runningAttempts += stats.getRunningAttempts();
            failedAttempts += stats.getFailedAttempts();
            expiredAttempts += stats.getExpiredAttempts();
        }

        return new TaskDetailStore.TaskMessageAttemptStats(
                totalAttempts,
                activeAttempts,
                runningAttempts,
                failedAttempts,
                expiredAttempts
        );
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
        private final java.util.HashSet<String> nonFinalMessageIds = new java.util.HashSet<>();
        private final Map<String, TaskMsgStatus> statusByMessageId = new ConcurrentHashMap<>();
        private int successCount;
        private int failedCount;
        private int expiredCount;
        private int processingCount;

        private synchronized void add(TaskMsg taskMsg) {
            TaskMsg previous = messagesById.putIfAbsent(taskMsg.getMessageId(), taskMsg);
            if (previous == null) {
                orderedMsgIds.addLast(taskMsg.getMessageId());
                reconcileMessageState(taskMsg.getMessageId(), taskMsg.getStatus());
                return;
            }
            messagesById.put(taskMsg.getMessageId(), taskMsg);
            reconcileMessageState(taskMsg.getMessageId(), taskMsg.getStatus());
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
            reconcileMessageState(taskMsg.getMessageId(), taskMsg.getStatus());
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

        private synchronized List<TaskMsg> snapshot(int limit) {
            List<TaskMsg> snapshot = new ArrayList<>(Math.min(messagesById.size(), limit));
            for (String messageId : orderedMsgIds) {
                if (snapshot.size() >= limit) {
                    break;
                }
                TaskMsg message = messagesById.get(messageId);
                if (message != null) {
                    snapshot.add(message);
                }
            }
            return snapshot;
        }

        private synchronized List<TaskMsg> snapshotNonFinal() {
            List<TaskMsg> snapshot = new ArrayList<>(nonFinalMessageIds.size());
            for (String messageId : nonFinalMessageIds) {
                TaskMsg message = messagesById.get(messageId);
                if (message != null
                        && message.getStatus() != null
                        && !message.getStatus().isFinal()) {
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

        private void reconcileMessageState(String messageId, TaskMsgStatus newStatus) {
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
        private final Map<String, TaskMsgAttempt> attemptsById = new ConcurrentHashMap<>();
        private final ConcurrentLinkedDeque<String> orderedAttemptIds = new ConcurrentLinkedDeque<>();
        private final Map<String, TaskMsgAttemptStatus> statusByAttemptId = new ConcurrentHashMap<>();
        private String latestActiveAttemptId;
        private int activeAttemptCount;
        private int runningAttemptCount;
        private int failedAttemptCount;
        private int expiredAttemptCount;

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
            reconcileAttemptState(
                    attempt.getAttemptId(),
                    statusByAttemptId.put(attempt.getAttemptId(), attempt.getStatus()),
                    attempt.getStatus()
            );
        }

        private synchronized boolean update(TaskMsgAttempt attempt) {
            if (attempt == null || attempt.getAttemptId() == null || !attemptsById.containsKey(attempt.getAttemptId())) {
                return false;
            }
            attemptsById.put(attempt.getAttemptId(), attempt);
            reconcileAttemptState(
                    attempt.getAttemptId(),
                    statusByAttemptId.put(attempt.getAttemptId(), attempt.getStatus()),
                    attempt.getStatus()
            );
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

        private synchronized TaskDetailStore.TaskMessageAttemptStats stats() {
            return new TaskDetailStore.TaskMessageAttemptStats(
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
