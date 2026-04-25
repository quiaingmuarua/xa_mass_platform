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
    public int countPendingDispatchableMessages(String taskId) {
        MessageBucket bucket = taskMessages.get(taskId);
        return bucket != null ? (int) bucket.countByStatus(TaskMsgStatus.INIT) : 0;
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
        long success = bucket.countByStatus(TaskMsgStatus.SUCCESS);
        long failed = bucket.countByStatus(TaskMsgStatus.FAILED);
        long expired = bucket.countByStatus(TaskMsgStatus.EXPIRED);
        long processing = bucket.countProcessing();

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

        private void add(TaskMsg taskMsg) {
            TaskMsg previous = messagesById.putIfAbsent(taskMsg.getMessageId(), taskMsg);
            if (previous == null) {
                orderedMsgIds.addLast(taskMsg.getMessageId());
                return;
            }
            messagesById.put(taskMsg.getMessageId(), taskMsg);
        }

        private Optional<TaskMsg> get(String messageId) {
            return Optional.ofNullable(messagesById.get(messageId));
        }

        private boolean update(TaskMsg taskMsg) {
            return messagesById.replace(taskMsg.getMessageId(), taskMsg) != null;
        }

        private List<TaskMsg> snapshot() {
            List<TaskMsg> snapshot = new ArrayList<>(messagesById.size());
            for (String messageId : orderedMsgIds) {
                TaskMsg message = messagesById.get(messageId);
                if (message != null) {
                    snapshot.add(message);
                }
            }
            return snapshot;
        }

        private int size() {
            return messagesById.size();
        }

        private long countByStatus(TaskMsgStatus status) {
            long count = 0;
            for (String messageId : orderedMsgIds) {
                TaskMsg message = messagesById.get(messageId);
                if (message != null && message.getStatus() == status) {
                    count++;
                }
            }
            return count;
        }

        private long countProcessing() {
            long count = 0;
            for (String messageId : orderedMsgIds) {
                TaskMsg message = messagesById.get(messageId);
                if (message != null && message.isProcessing()) {
                    count++;
                }
            }
            return count;
        }
    }

    private static final class AttemptBucket {
        private final Map<String, TaskMsgAttempt> attemptsById = new ConcurrentHashMap<>();
        private final ConcurrentLinkedDeque<String> orderedAttemptIds = new ConcurrentLinkedDeque<>();

        private void add(TaskMsgAttempt attempt) {
            if (attempt == null || attempt.getAttemptId() == null) {
                return;
            }
            TaskMsgAttempt previous = attemptsById.putIfAbsent(attempt.getAttemptId(), attempt);
            if (previous == null) {
                orderedAttemptIds.addLast(attempt.getAttemptId());
                return;
            }
            attemptsById.put(attempt.getAttemptId(), attempt);
        }

        private boolean update(TaskMsgAttempt attempt) {
            return attemptsById.replace(attempt.getAttemptId(), attempt) != null;
        }

        private List<TaskMsgAttempt> snapshot() {
            List<TaskMsgAttempt> snapshot = new ArrayList<>(attemptsById.size());
            for (String attemptId : orderedAttemptIds) {
                TaskMsgAttempt attempt = attemptsById.get(attemptId);
                if (attempt != null) {
                    snapshot.add(attempt);
                }
            }
            return snapshot;
        }

        private Optional<TaskMsgAttempt> latest() {
            String latestAttemptId = orderedAttemptIds.peekLast();
            if (latestAttemptId == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(attemptsById.get(latestAttemptId));
        }

        private Optional<TaskMsgAttempt> latestActive() {
            java.util.Iterator<String> iterator = orderedAttemptIds.descendingIterator();
            while (iterator.hasNext()) {
                String attemptId = iterator.next();
                TaskMsgAttempt attempt = attemptsById.get(attemptId);
                if (attempt != null && attempt.getStatus() != null && attempt.getStatus().isActive()) {
                    return Optional.of(attempt);
                }
            }
            return Optional.empty();
        }
    }
}
