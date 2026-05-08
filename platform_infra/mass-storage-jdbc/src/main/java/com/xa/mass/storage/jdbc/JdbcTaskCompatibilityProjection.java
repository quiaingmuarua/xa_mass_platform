package com.xa.mass.storage.jdbc;

import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.storage.api.TaskDetailStore;

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
        upsertTaskMessageProjection(taskId,
                taskMsg != null ? TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(taskMsg) : null);
    }

    boolean upsertTaskMessageProjection(String taskId, TaskDetailStore.TaskMessageProjection projection) {
        MessageBucket bucket = taskMessages.get(taskId);
        if (bucket != null && projection != null && projection.messageId() != null) {
            bucket.add(projection);
            taskMessageAttempts.computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>())
                    .putIfAbsent(projection.messageId(), new AttemptBucket());
            return true;
        }
        return false;
    }

    List<TaskMsg> getTaskMessages(String taskId) {
        return getTaskMessageProjections(taskId).stream()
                .map(TaskDetailStore.TaskMessageProjection::toCompatibilityProjection)
                .toList();
    }

    List<TaskDetailStore.TaskMessageProjection> getTaskMessageProjections(String taskId) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? bucket.snapshot() : List.of();
    }

    List<TaskMsg> getTaskMessages(String taskId, int limit) {
        return getTaskMessageProjections(taskId, limit).stream()
                .map(TaskDetailStore.TaskMessageProjection::toCompatibilityProjection)
                .toList();
    }

    List<TaskDetailStore.TaskMessageProjection> getTaskMessageProjections(String taskId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? bucket.snapshot(limit) : List.of();
    }

    List<TaskMsg> getNonFinalTaskMessages(String taskId) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? bucket.snapshotNonFinal().stream()
                .map(TaskDetailStore.TaskMessageProjection::toCompatibilityProjection)
                .toList() : List.of();
    }

    long countTaskMessages(String taskId) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? bucket.size() : 0;
    }

    Optional<TaskMsg> getTaskMessage(String taskId, String messageId) {
        return getTaskMessageProjection(taskId, messageId)
                .map(TaskDetailStore.TaskMessageProjection::toCompatibilityProjection);
    }

    Optional<TaskDetailStore.TaskMessageProjection> getTaskMessageProjection(String taskId, String messageId) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? bucket.get(messageId) : Optional.empty();
    }

    boolean updateTaskMessage(String taskId, TaskMsg taskMsg) {
        MessageBucket bucket = taskMessages.get(taskId);
        TaskDetailStore.TaskMessageProjection projection =
                taskMsg != null ? TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(taskMsg) : null;
        return bucket != null && projection != null && projection.messageId() != null && bucket.update(projection);
    }

    void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        upsertTaskMessageAttemptProjection(taskId, messageId,
                attempt != null ? TaskDetailStore.TaskMessageAttemptProjection.fromCompatibilityProjection(attempt) : null);
    }

    boolean upsertTaskMessageAttemptProjection(String taskId,
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

    List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId) {
        AttemptBucket bucket = getAttemptBucket(taskId, messageId);
        return bucket != null ? bucket.snapshot().stream()
                .map(TaskDetailStore.TaskMessageAttemptProjection::toCompatibilityProjection)
                .toList() : List.of();
    }

    Optional<TaskMsgAttempt> getLatestTaskMessageAttempt(String taskId, String messageId) {
        return getLatestTaskMessageAttemptProjection(taskId, messageId)
                .map(TaskDetailStore.TaskMessageAttemptProjection::toCompatibilityProjection);
    }

    Optional<TaskDetailStore.TaskMessageAttemptProjection> getLatestTaskMessageAttemptProjection(String taskId,
                                                                                                 String messageId) {
        AttemptBucket bucket = getAttemptBucket(taskId, messageId);
        return bucket != null ? bucket.latest() : Optional.empty();
    }

    Optional<TaskMsgAttempt> getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        AttemptBucket bucket = getAttemptBucket(taskId, messageId);
        return bucket != null ? bucket.latestActive().map(TaskDetailStore.TaskMessageAttemptProjection::toCompatibilityProjection)
                : Optional.empty();
    }

    TaskDetailStore.TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId) {
        AttemptBucket bucket = getAttemptBucket(taskId, messageId);
        return bucket != null ? bucket.stats() : new TaskDetailStore.TaskMessageAttemptStats(0, 0, 0, 0, 0);
    }

    boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        AttemptBucket bucket = getAttemptBucket(taskId, messageId);
        return bucket != null
                && attempt != null
                && attempt.getAttemptId() != null
                && bucket.update(TaskDetailStore.TaskMessageAttemptProjection.fromCompatibilityProjection(attempt));
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
