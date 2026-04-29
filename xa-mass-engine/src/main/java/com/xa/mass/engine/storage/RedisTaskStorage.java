package com.xa.mass.engine.storage;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.storage.api.TaskStorage;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Redis-backed task storage placeholder.
 *
 * <p>This class is intentionally fail-fast. The active runtime path keeps task
 * control-plane persistence on the server-owned JDBC adapter and keeps runtime
 * hot-path queue ownership out of engine-local Redis storage classes.</p>
 *
 * @deprecated Not implemented. Do not wire this class into the active mainline.
 */
@Deprecated
public class RedisTaskStorage implements TaskStorage {

    @Override
    public void saveTask(Task task) {
        throw unsupported();
    }

    @Override
    public Optional<Task> getTask(String taskId) {
        throw unsupported();
    }

    @Override
    public boolean updateTask(Task task) {
        throw unsupported();
    }

    @Override
    public boolean deleteTask(String taskId) {
        throw unsupported();
    }

    @Override
    public List<Task> getAllTasks() {
        throw unsupported();
    }

    @Override
    public List<Task> getTasksByStatus(TaskStatus status) {
        throw unsupported();
    }

    @Override
    public List<Task> getTasksByProject(String project) {
        throw unsupported();
    }

    @Override
    public List<Task> getSchedulableTasks() {
        throw unsupported();
    }

    @Override
    public List<Task> pollExpiredMaxRuntimeTasks(LocalDateTime now, int limit) {
        throw unsupported();
    }

    @Override
    public void addTaskMessage(String taskId, TaskMsg taskMsg) {
        throw unsupported();
    }

    @Override
    public List<TaskMsg> getTaskMessages(String taskId) {
        throw unsupported();
    }

    @Override
    public List<TaskMsg> getTaskMessages(String taskId, int limit) {
        throw unsupported();
    }

    @Override
    public List<TaskMsg> getNonFinalTaskMessages(String taskId) {
        throw unsupported();
    }

    @Override
    public long countTaskMessages(String taskId) {
        throw unsupported();
    }

    @Override
    public Optional<TaskMsg> getTaskMessage(String taskId, String messageId) {
        throw unsupported();
    }

    @Override
    public boolean updateTaskMessage(String taskId, TaskMsg taskMsg) {
        throw unsupported();
    }

    @Override
    public void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        throw unsupported();
    }

    @Override
    public List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId) {
        throw unsupported();
    }

    @Override
    public Optional<TaskMsgAttempt> getLatestTaskMessageAttempt(String taskId, String messageId) {
        throw unsupported();
    }

    @Override
    public Optional<TaskMsgAttempt> getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        throw unsupported();
    }

    @Override
    public TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId) {
        throw unsupported();
    }

    @Override
    public boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        throw unsupported();
    }

    @Override
    public TaskMessageStats getTaskMessageStats(String taskId) {
        throw unsupported();
    }

    @Override
    public TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId) {
        throw unsupported();
    }

    private UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException(
                "Redis task storage is not implemented. Active mainline uses in-memory engine projection "
                        + "plus server-owned JDBC task persistence.");
    }
}
