package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;

import java.util.List;
import java.util.Objects;

/**
 * Preferred engine bounded-read surface for cross-module task inspection.
 *
 * <p>Read tiers exposed here are intentionally narrow:
 * task shell / aggregate reads are mainline, bounded {@link TaskMsg} /
 * {@link TaskMsgAttempt} reads remain shell/debug compatibility helpers, and
 * projection audit stays diagnostic-only. This surface must not become a
 * pagination/history or runtime-correctness contract.
 */
public class TaskQueryService {

    private final TaskQueryPort taskQueries;

    public TaskQueryService(TaskManager taskManager) {
        this(new TaskManagerQueryPort(taskManager));
    }

    public TaskQueryService(TaskQueryPort taskQueries) {
        this.taskQueries = Objects.requireNonNull(taskQueries, "taskQueries");
    }

    public Task getTask(String taskId) {
        return taskQueries.getTask(taskId);
    }

    public List<Task> listTasksPaged(int offset, int limit) {
        return taskQueries.listTasksPaged(offset, limit);
    }

    public List<Task> getTasksByStatus(TaskStatus status) {
        return taskQueries.getTasksByStatus(status);
    }

    public List<TaskMsg> getTaskMessages(String taskId, int limit) {
        return taskQueries.getTaskMessages(taskId, limit);
    }

    public long countTaskMessages(String taskId) {
        return taskQueries.countTaskMessages(taskId);
    }

    public TaskMsg getTaskMessage(String taskId, String messageId) {
        return taskQueries.getTaskMessage(taskId, messageId);
    }

    public List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId) {
        return taskQueries.getTaskMessageAttempts(taskId, messageId);
    }

    public TaskMsgAttempt getLatestTaskMessageAttempt(String taskId, String messageId) {
        return taskQueries.getLatestTaskMessageAttempt(taskId, messageId);
    }

    public TaskMsgAttempt getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
        return taskQueries.getLatestActiveTaskMessageAttempt(taskId, messageId);
    }

    public TaskStateResolutionResult resolveTaskState(String taskId) {
        return taskQueries.resolveTaskState(taskId);
    }

    public TaskStateValidationResult validateTaskState(String taskId) {
        return taskQueries.validateTaskState(taskId);
    }

}
