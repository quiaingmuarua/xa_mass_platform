package com.xa.mass.storage.jdbc;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.TaskStorage;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC adapter for durable task truth.
 *
 * <p>High-frequency message and attempt detail stays in a process-local
 * compatibility projection. Do not expand this adapter into a cross-task
 * message analytics surface; high-volume detail belongs in queues, trace, or
 * audit sinks.</p>
 */
public class JdbcTaskStorage extends JdbcStorageSupport implements TaskStorage, TaskDetailStore {

    private final JdbcDialect dialect;
    private final JdbcTaskCompatibilityProjection runtimeProjection = new JdbcTaskCompatibilityProjection();

    public JdbcTaskStorage(DataSource dataSource, JdbcDialect dialect) {
        super(dataSource);
        this.dialect = dialect;
    }

    @Override
    public synchronized void saveTask(Task task) {
        if (task == null || task.getTid() == null) {
            throw new IllegalArgumentException("task and task id are required");
        }
        try (var conn = connection(); var ps = conn.prepareStatement(dialect.taskUpsertSql())) {
            bindTask(ps, task);
            ps.executeUpdate();
            runtimeProjection.ensureTask(task.getTid());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save task " + task.getTid(), e);
        }
    }

    @Override
    public Optional<Task> getTask(String taskId) {
        return queryOneTask("SELECT json FROM xa_task WHERE task_id = ?", taskId);
    }

    @Override
    public synchronized boolean updateTask(Task task) {
        if (task == null || task.getTid() == null) {
            return false;
        }
        try (var conn = connection(); var ps = conn.prepareStatement("""
                UPDATE xa_task SET status = ?, project = ?, schedulable = ?, max_runtime_deadline = ?, json = ?
                WHERE task_id = ?
                """)) {
            ps.setString(1, task.getStatus() == null ? null : task.getStatus().name());
            ps.setString(2, task.getProject());
            ps.setBoolean(3, task.isSchedulable());
            setTimestamp(ps, 4, maxRuntimeDeadline(task));
            ps.setString(5, json(task));
            ps.setString(6, task.getTid());
            boolean updated = ps.executeUpdate() > 0;
            if (updated) {
                runtimeProjection.ensureTask(task.getTid());
            }
            return updated;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update task " + task.getTid(), e);
        }
    }

    @Override
    public synchronized boolean deleteTask(String taskId) {
        try (var conn = connection()) {
            boolean deleted = executeUpdate(conn, "DELETE FROM xa_task WHERE task_id = ?", taskId) > 0;
            if (deleted) {
                runtimeProjection.deleteTask(taskId);
            }
            return deleted;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete task " + taskId, e);
        }
    }

    @Override
    public List<Task> listTasksPaged(int offset, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        try (var conn = connection(); var ps = conn.prepareStatement(
                "SELECT json FROM xa_task ORDER BY create_time DESC LIMIT ? OFFSET ?")) {
            ps.setInt(1, limit);
            ps.setInt(2, Math.max(0, offset));
            return readTasks(ps);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list tasks paged", e);
        }
    }

    @Override
    public List<Task> getTasksByStatus(TaskStatus status) {
        return queryTasks("SELECT json FROM xa_task WHERE status = ?", status == null ? null : status.name());
    }

    @Override
    public List<Task> getTasksByProject(String project) {
        return queryTasks("SELECT json FROM xa_task WHERE project = ?", project);
    }

    @Override
    public List<Task> getSchedulableTasks() {
        return queryTasks("SELECT json FROM xa_task WHERE schedulable = TRUE");
    }

    @Override
    public List<Task> pollExpiredMaxRuntimeTasks(LocalDateTime now, int limit) {
        if (now == null || limit <= 0) {
            return List.of();
        }
        try (var conn = connection(); var ps = conn.prepareStatement("""
                SELECT json FROM xa_task
                WHERE max_runtime_deadline IS NOT NULL AND max_runtime_deadline < ?
                ORDER BY max_runtime_deadline
                LIMIT ?
                """)) {
            ps.setTimestamp(1, Timestamp.valueOf(now));
            ps.setInt(2, limit);
            return readTasks(ps);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to poll expired max-runtime tasks", e);
        }
    }

    @Override
    public synchronized void addTaskMessage(String taskId, TaskMsg taskMsg) {
        runtimeProjection.addTaskMessage(taskId, taskMsg);
    }

    @Override
    public synchronized boolean upsertTaskMessageProjection(String taskId, TaskDetailStore.TaskMessageProjection projection) {
        return runtimeProjection.upsertTaskMessageProjection(taskId, projection);
    }

    @Override
    public List<TaskMsg> getTaskMessages(String taskId) {
        return runtimeProjection.getTaskMessages(taskId);
    }

    @Override
    public List<TaskDetailStore.TaskMessageProjection> getTaskMessageProjections(String taskId) {
        return runtimeProjection.getTaskMessageProjections(taskId);
    }

    @Override
    public List<TaskMsg> getTaskMessages(String taskId, int limit) {
        return runtimeProjection.getTaskMessages(taskId, limit);
    }

    @Override
    public List<TaskDetailStore.TaskMessageProjection> getTaskMessageProjections(String taskId, int limit) {
        return runtimeProjection.getTaskMessageProjections(taskId, limit);
    }

    @Override
    public List<TaskMsg> getNonFinalTaskMessages(String taskId) {
        return runtimeProjection.getNonFinalTaskMessages(taskId);
    }

    @Override
    public long countTaskMessages(String taskId) {
        return runtimeProjection.countTaskMessages(taskId);
    }

    @Override
    public Optional<TaskMsg> getTaskMessage(String taskId, String messageId) {
        return runtimeProjection.getTaskMessage(taskId, messageId);
    }

    @Override
    public Optional<TaskDetailStore.TaskMessageProjection> getTaskMessageProjection(String taskId, String messageId) {
        return runtimeProjection.getTaskMessageProjection(taskId, messageId);
    }

    @Override
    public synchronized boolean updateTaskMessage(String taskId, TaskMsg taskMsg) {
        return runtimeProjection.updateTaskMessage(taskId, taskMsg);
    }

    @Override
    public synchronized void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        runtimeProjection.addTaskMessageAttempt(taskId, messageId, attempt);
    }

    @Override
    public synchronized boolean upsertTaskMessageAttemptProjection(String taskId,
                                                                   String messageId,
                                                                   TaskDetailStore.TaskMessageAttemptProjection projection) {
        return runtimeProjection.upsertTaskMessageAttemptProjection(taskId, messageId, projection);
    }

    @Override
    public List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId) {
        return runtimeProjection.getTaskMessageAttempts(taskId, messageId);
    }

    @Override
    public Optional<TaskMsgAttempt> getLatestTaskMessageAttempt(String taskId, String messageId) {
        return runtimeProjection.getLatestTaskMessageAttempt(taskId, messageId);
    }

    @Override
    public Optional<TaskDetailStore.TaskMessageAttemptProjection> getLatestTaskMessageAttemptProjection(String taskId,
                                                                                                         String messageId) {
        return runtimeProjection.getLatestTaskMessageAttemptProjection(taskId, messageId);
    }

    @Override
    public Optional<TaskMsgAttempt> getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        return runtimeProjection.getLatestActiveTaskMessageAttempt(taskId, messageId);
    }

    @Override
    public TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId) {
        return runtimeProjection.getTaskMessageAttemptStats(taskId, messageId);
    }

    @Override
    public synchronized boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
        return runtimeProjection.updateTaskMessageAttempt(taskId, messageId, attempt);
    }

    @Override
    public TaskMessageStats getTaskMessageStats(String taskId) {
        return runtimeProjection.getTaskMessageStats(taskId);
    }

    @Override
    public TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId) {
        return runtimeProjection.getTaskMessageAttemptStats(taskId);
    }

    private Optional<Task> queryOneTask(String sql, String arg) {
        List<Task> tasks = queryTasks(sql, arg);
        return tasks.stream().findFirst();
    }

    private List<Task> queryTasks(String sql, String... args) {
        try (var conn = connection(); var ps = conn.prepareStatement(sql)) {
            bindArgs(ps, args);
            return readTasks(ps);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to query tasks", e);
        }
    }

    private List<Task> readTasks(PreparedStatement ps) throws Exception {
        List<Task> tasks = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tasks.add(readJson(rs.getString(1), Task.class));
            }
        }
        return tasks;
    }

    private void bindTask(PreparedStatement ps, Task task) throws Exception {
        ps.setString(1, task.getTid());
        ps.setString(2, task.getStatus() == null ? null : task.getStatus().name());
        ps.setString(3, task.getProject());
        ps.setBoolean(4, task.isSchedulable());
        setTimestamp(ps, 5, maxRuntimeDeadline(task));
        ps.setString(6, json(task));
    }

    private void bindArgs(PreparedStatement ps, String... args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            ps.setString(i + 1, args[i]);
        }
    }

    private int executeUpdate(java.sql.Connection conn, String sql, String arg) throws Exception {
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            return ps.executeUpdate();
        }
    }

    private void setTimestamp(PreparedStatement ps, int index, LocalDateTime value) throws Exception {
        if (value == null) {
            ps.setTimestamp(index, null);
        } else {
            ps.setTimestamp(index, Timestamp.valueOf(value));
        }
    }

    private LocalDateTime maxRuntimeDeadline(Task task) {
        if (task == null || task.getStatus() == null || task.getStatus().isFinal()
                || task.getMaxRuntimeSeconds() <= 0 || task.getStartTime() == null) {
            return null;
        }
        return task.getStartTime().plusSeconds(task.getMaxRuntimeSeconds());
    }
}

