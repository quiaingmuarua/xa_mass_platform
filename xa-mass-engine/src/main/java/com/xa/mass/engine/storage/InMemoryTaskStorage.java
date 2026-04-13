package com.xa.mass.engine.storage;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;

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

    @Override
    public void saveTask(Task task) {
        tasks.put(task.getTid(), task);
        taskMessages.put(task.getTid(), new CopyOnWriteArrayList<>());
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
        return removed != null;
    }

    @Override
    public List<Task> getAllTasks() {
        return new CopyOnWriteArrayList<>(tasks.values());
    }

    @Override
    public List<Task> getTasksByStatus(String status) {
        TaskStatus taskStatus = TaskStatus.valueOf(status);
        return tasks.values().stream()
                .filter(task -> task.getStatus() == taskStatus)
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
    public TaskMessageStats getTaskMessageStats(String taskId) {
        List<TaskMsg> messages = getTaskMessages(taskId);

        long total = messages.size();
        long success = messages.stream().filter(TaskMsg::isSuccess).count();
        long failed = messages.stream().filter(TaskMsg::isFailed).count();
        long processing = messages.stream().filter(TaskMsg::isProcessing).count();

        return new TaskMessageStats(total, success, failed, processing);
    }
} 
