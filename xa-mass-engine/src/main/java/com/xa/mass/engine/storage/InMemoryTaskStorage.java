package com.xa.mass.engine.storage;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 内存任务存储实现
 * 使用ConcurrentHashMap和CopyOnWriteArrayList保证线程安全
 */
public class InMemoryTaskStorage implements TaskStorage {

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<String, List<TaskMsg>> taskMessages = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<TaskMsgAttempt>>> taskMessageAttempts = new ConcurrentHashMap<>();

    @Override
    public void saveTask(Task task) {
        tasks.put(task.getTid(), task);
        taskMessages.put(task.getTid(), new CopyOnWriteArrayList<>());
        taskMessageAttempts.put(task.getTid(), new ConcurrentHashMap<>());
    }

    @Override
    public Optional<Task> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public boolean updateTask(Task task) {
        if (task.getTid() == null || !tasks.containsKey(task.getTid())) {
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
        return new CopyOnWriteArrayList<>(tasks.values());
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
        List<TaskMsg> messages = taskMessages.get(taskId);
        if (messages != null) {
            messages.add(taskMsg);
            taskMessageAttempts.computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>())
                    .putIfAbsent(taskMsg.getMsgId(), new CopyOnWriteArrayList<>());
        }
    }

    @Override
    public List<TaskMsg> getTaskMessages(String taskId) {
        List<TaskMsg> messages = taskMessages.get(taskId);
        return messages != null ? new CopyOnWriteArrayList<>(messages) : new CopyOnWriteArrayList<>();
    }

    @Override
    public Optional<TaskMsg> getTaskMessage(String taskId, String msgId) {
        List<TaskMsg> messages = taskMessages.get(taskId);
        if (messages == null) {
            return Optional.empty();
        }
        return messages.stream()
                .filter(message -> msgId != null && msgId.equals(message.getMsgId()))
                .findFirst();
    }

    @Override
    public boolean updateTaskMessage(String taskId, TaskMsg taskMsg) {
        List<TaskMsg> messages = taskMessages.get(taskId);
        if (messages == null || taskMsg == null || taskMsg.getMsgId() == null) {
            return false;
        }
        for (int i = 0; i < messages.size(); i++) {
            TaskMsg existing = messages.get(i);
            if (taskMsg.getMsgId().equals(existing.getMsgId())) {
                messages.set(i, taskMsg);
                return true;
            }
        }
        return false;
    }

    @Override
    public void addTaskMessageAttempt(String taskId, String msgId, TaskMsgAttempt attempt) {
        taskMessageAttempts
                .computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(msgId, ignored -> new CopyOnWriteArrayList<>())
                .add(attempt);
    }

    @Override
    public List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String msgId) {
        Map<String, List<TaskMsgAttempt>> attemptsByMsg = taskMessageAttempts.get(taskId);
        if (attemptsByMsg == null) {
            return new CopyOnWriteArrayList<>();
        }
        List<TaskMsgAttempt> attempts = attemptsByMsg.get(msgId);
        return attempts != null ? new CopyOnWriteArrayList<>(attempts) : new CopyOnWriteArrayList<>();
    }

    @Override
    public Optional<TaskMsgAttempt> getLatestTaskMessageAttempt(String taskId, String msgId) {
        List<TaskMsgAttempt> attempts = getTaskMessageAttempts(taskId, msgId);
        if (attempts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(attempts.get(attempts.size() - 1));
    }

    @Override
    public boolean updateTaskMessageAttempt(String taskId, String msgId, TaskMsgAttempt attempt) {
        Map<String, List<TaskMsgAttempt>> attemptsByMsg = taskMessageAttempts.get(taskId);
        if (attemptsByMsg == null || attempt == null || attempt.getAttemptId() == null) {
            return false;
        }
        List<TaskMsgAttempt> attempts = attemptsByMsg.get(msgId);
        if (attempts == null) {
            return false;
        }
        for (int i = 0; i < attempts.size(); i++) {
            TaskMsgAttempt existing = attempts.get(i);
            if (attempt.getAttemptId().equals(existing.getAttemptId())) {
                attempts.set(i, attempt);
                return true;
            }
        }
        return false;
    }

    @Override
    public TaskMessageStats getTaskMessageStats(String taskId) {
        List<TaskMsg> messages = getTaskMessages(taskId);

        long total = messages.size();
        long success = messages.stream().filter(TaskMsg::isSuccess).count();
        long failed = messages.stream().filter(m -> m.getStatus() == TaskMsgStatus.FAILED).count();
        long expired = messages.stream().filter(m -> m.getStatus() == TaskMsgStatus.EXPIRED).count();
        long processing = messages.stream().filter(TaskMsg::isProcessing).count();

        return new TaskMessageStats(total, success, failed, expired, processing);
    }

    @Override
    public TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId) {
        Map<String, List<TaskMsgAttempt>> attemptsByMsg = taskMessageAttempts.get(taskId);
        if (attemptsByMsg == null) {
            return new TaskMessageAttemptStats(0, 0, 0, 0, 0);
        }
        List<TaskMsgAttempt> attempts = attemptsByMsg.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
        long totalAttempts = attempts.size();
        long activeAttempts = attempts.stream().filter(attempt -> attempt.getStatus() != null && attempt.getStatus().isActive()).count();
        long runningAttempts = attempts.stream().filter(attempt -> attempt.getStatus() == TaskMsgAttemptStatus.RUNNING).count();
        long failedAttempts = attempts.stream().filter(attempt -> attempt.getStatus() == TaskMsgAttemptStatus.FAILED).count();
        long expiredAttempts = attempts.stream().filter(attempt -> attempt.getStatus() == TaskMsgAttemptStatus.EXPIRED).count();
        return new TaskMessageAttemptStats(totalAttempts, activeAttempts, runningAttempts, failedAttempts, expiredAttempts);
    }
}
