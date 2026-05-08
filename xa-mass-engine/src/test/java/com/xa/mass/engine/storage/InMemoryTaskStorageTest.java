package com.xa.mass.engine.storage;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.TaskStorage;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTaskStorageTest {

    @Test
    void latestActiveAttemptIgnoresNewerFinalAttempts() {
        InMemoryTaskStorage storage = new InMemoryTaskStorage();

        TaskMsgAttempt runningAttempt = attempt("attempt-1", 1, TaskMsgAttemptStatus.RUNNING);
        TaskMsgAttempt failedAttempt = attempt("attempt-2", 2, TaskMsgAttemptStatus.FAILED);

        storage.upsertTaskMessageAttemptProjection("task-1", "msg-1",
                TaskDetailStore.TaskMessageAttemptProjection.fromCompatibilityProjection(runningAttempt));
        storage.upsertTaskMessageAttemptProjection("task-1", "msg-1",
                TaskDetailStore.TaskMessageAttemptProjection.fromCompatibilityProjection(failedAttempt));

        Optional<TaskDetailStore.TaskMessageAttemptProjection> latestActive =
                storage.getLatestActiveTaskMessageAttemptProjection("task-1", "msg-1");

        assertTrue(latestActive.isPresent());
        assertEquals("attempt-1", latestActive.get().attemptId());
    }

    @Test
    void perMessageAttemptStatsTrackIndexedAttemptStateCounts() {
        InMemoryTaskStorage storage = new InMemoryTaskStorage();

        TaskMsgAttempt runningAttempt = attempt("attempt-1", 1, TaskMsgAttemptStatus.RUNNING);
        TaskMsgAttempt failedAttempt = attempt("attempt-2", 2, TaskMsgAttemptStatus.FAILED);

        storage.upsertTaskMessageAttemptProjection("task-1", "msg-1",
                TaskDetailStore.TaskMessageAttemptProjection.fromCompatibilityProjection(runningAttempt));
        storage.upsertTaskMessageAttemptProjection("task-1", "msg-1",
                TaskDetailStore.TaskMessageAttemptProjection.fromCompatibilityProjection(failedAttempt));

        TaskDetailStore.TaskMessageAttemptStats initialStats = storage.getTaskMessageAttemptStats("task-1", "msg-1");
        assertEquals(2, initialStats.getTotalAttempts());
        assertEquals(1, initialStats.getActiveAttempts());
        assertEquals(1, initialStats.getRunningAttempts());
        assertEquals(1, initialStats.getFailedAttempts());
        assertEquals(0, initialStats.getExpiredAttempts());

        runningAttempt.setStatus(TaskMsgAttemptStatus.SUCCEEDED);
        assertTrue(storage.upsertTaskMessageAttemptProjection("task-1", "msg-1",
                TaskDetailStore.TaskMessageAttemptProjection.fromCompatibilityProjection(runningAttempt)));

        TaskDetailStore.TaskMessageAttemptStats updatedStats = storage.getTaskMessageAttemptStats("task-1", "msg-1");
        assertEquals(2, updatedStats.getTotalAttempts());
        assertEquals(0, updatedStats.getActiveAttempts());
        assertEquals(0, updatedStats.getRunningAttempts());
        assertEquals(1, updatedStats.getFailedAttempts());
        assertEquals(0, updatedStats.getExpiredAttempts());
    }

    @Test
    void pollExpiredMaxRuntimeTasksUsesDeadlineIndex() {
        InMemoryTaskStorage storage = new InMemoryTaskStorage();
        LocalDateTime now = LocalDateTime.now();
        Task expired = runningTask("expired", now.minusSeconds(20), 10);
        Task future = runningTask("future", now.minusSeconds(5), 60);
        Task unlimited = runningTask("unlimited", now.minusSeconds(100), 0);
        storage.saveTask(expired);
        storage.saveTask(future);
        storage.saveTask(unlimited);

        List<Task> tasks = storage.pollExpiredMaxRuntimeTasks(now, 10);

        assertEquals(List.of("expired"), tasks.stream().map(Task::getTid).toList());
        assertTrue(storage.pollExpiredMaxRuntimeTasks(now.plusSeconds(1), 10).isEmpty());
    }

    @Test
    void updateTaskRefreshesMaxRuntimeDeadlineAfterInPlaceMutation() {
        InMemoryTaskStorage storage = new InMemoryTaskStorage();
        LocalDateTime now = LocalDateTime.now();
        Task task = runningTask("mutable", now, 60);
        storage.saveTask(task);

        Task stored = storage.getTask("mutable").orElseThrow();
        stored.setStartTime(now.minusSeconds(120));
        assertTrue(storage.updateTask(stored));

        List<Task> tasks = storage.pollExpiredMaxRuntimeTasks(now, 10);

        assertEquals(List.of("mutable"), tasks.stream().map(Task::getTid).toList());
    }

    @Test
    void updateTaskRefreshesProjectIndexAfterInPlaceMutation() {
        InMemoryTaskStorage storage = new InMemoryTaskStorage();
        Task task = runningTask("project-mutable", LocalDateTime.now(), 60);
        task.setProject("demoApp");
        storage.saveTask(task);

        Task stored = storage.getTask("project-mutable").orElseThrow();
        stored.setProject("crawlerApp");
        assertTrue(storage.updateTask(stored));

        assertTrue(storage.getTasksByProject("demoApp").isEmpty());
        assertEquals(List.of("project-mutable"),
                storage.getTasksByProject("crawlerApp").stream().map(Task::getTid).toList());
    }

    @Test
    void terminalTaskIsRemovedFromMaxRuntimeDeadlineIndex() {
        InMemoryTaskStorage storage = new InMemoryTaskStorage();
        LocalDateTime now = LocalDateTime.now();
        Task task = runningTask("terminal", now.minusSeconds(120), 60);
        storage.saveTask(task);

        task.setStatus(TaskStatus.TERMINAL);
        assertTrue(storage.updateTask(task));

        assertTrue(storage.pollExpiredMaxRuntimeTasks(now, 10).isEmpty());
    }

    @Test
    void taskMessageProjectionFilteringExcludesTerminalMessages() {
        InMemoryTaskStorage storage = new InMemoryTaskStorage();
        storage.saveTask(runningTask("task-1", LocalDateTime.now(), 60));

        TaskMsg init = message("msg-init", TaskMsgStatus.INIT);
        TaskMsg assigned = message("msg-assigned", TaskMsgStatus.ASSIGNED);
        TaskMsg success = message("msg-success", TaskMsgStatus.SUCCESS);
        success.setFinalReason(TaskMsgFinalReason.BUSINESS_SUCCESS);
        TaskMsg failed = message("msg-failed", TaskMsgStatus.FAILED);
        failed.setFinalReason(TaskMsgFinalReason.MANUAL_CANCELLED);

        storage.upsertTaskMessageProjection("task-1", TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(init));
        storage.upsertTaskMessageProjection("task-1", TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(assigned));
        storage.upsertTaskMessageProjection("task-1", TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(success));
        storage.upsertTaskMessageProjection("task-1", TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(failed));

        assertEquals(java.util.Set.of("msg-init", "msg-assigned"),
                storage.getTaskMessageProjections("task-1").stream()
                        .filter(projection -> projection.status() == null || !projection.status().isFinal())
                        .map(TaskDetailStore.TaskMessageProjection::messageId)
                        .collect(java.util.stream.Collectors.toSet()));

        assertTrue(assigned.markAsExpired(TaskMsgFinalReason.MANUAL_CANCELLED));
        assertTrue(storage.upsertTaskMessageProjection("task-1",
                TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(assigned)));

        assertEquals(java.util.Set.of("msg-init"),
                storage.getTaskMessageProjections("task-1").stream()
                        .filter(projection -> projection.status() == null || !projection.status().isFinal())
                        .map(TaskDetailStore.TaskMessageProjection::messageId)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void deleteTaskReleasesMessageBucketsAttemptsAndPendingIndex() {
        InMemoryTaskStorage storage = new InMemoryTaskStorage();
        storage.saveTask(runningTask("task-1", LocalDateTime.now(), 60));

        TaskMsg init = message("msg-init", TaskMsgStatus.INIT);
        storage.upsertTaskMessageProjection("task-1", TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(init));
        storage.upsertTaskMessageAttemptProjection("task-1", "msg-init",
                TaskDetailStore.TaskMessageAttemptProjection.fromCompatibilityProjection(
                        attempt("attempt-1", 1, TaskMsgAttemptStatus.RUNNING)));

        assertTrue(storage.deleteTask("task-1"));
        assertTrue(storage.getTask("task-1").isEmpty());
        assertTrue(storage.getTaskMessageProjections("task-1").isEmpty());
        assertEquals(0, storage.getTaskMessageStats("task-1").getTotal());
        assertTrue(storage.getTaskMessageAttemptProjections("task-1", "msg-init").isEmpty());
    }

    @Test
    void saveTaskDoesNotResetExistingMessageAndAttemptBuckets() {
        InMemoryTaskStorage storage = new InMemoryTaskStorage();
        storage.saveTask(runningTask("task-1", LocalDateTime.now(), 60));

        TaskMsg init = message("msg-init", TaskMsgStatus.INIT);
        storage.upsertTaskMessageProjection("task-1", TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(init));
        storage.upsertTaskMessageAttemptProjection("task-1", "msg-init",
                TaskDetailStore.TaskMessageAttemptProjection.fromCompatibilityProjection(
                        attempt("attempt-1", 1, TaskMsgAttemptStatus.RUNNING)));

        Task replacement = runningTask("task-1", LocalDateTime.now(), 120);
        storage.saveTask(replacement);

        assertEquals(1, storage.getTaskMessageStats("task-1").getTotal());
        assertEquals(List.of("msg-init"),
                storage.getTaskMessageProjections("task-1").stream().map(TaskDetailStore.TaskMessageProjection::messageId).toList());
        assertEquals(1, storage.getTaskMessageAttemptProjections("task-1", "msg-init").size());
    }

    private TaskMsgAttempt attempt(String attemptId, int attemptNo, TaskMsgAttemptStatus status) {
        TaskMsgAttempt attempt = new TaskMsgAttempt();
        attempt.setAttemptId(attemptId);
        attempt.setAttemptNo(attemptNo);
        attempt.setTaskId("task-1");
        attempt.setMessageId("msg-1");
        attempt.setStatus(status);
        return attempt;
    }

    private TaskMsg message(String messageId, TaskMsgStatus status) {
        TaskMsg message = new TaskMsg();
        message.setTaskId("task-1");
        message.setMessageId(messageId);
        message.setStatus(status);
        return message;
    }

    private Task runningTask(String taskId, LocalDateTime startTime, int maxRuntimeSeconds) {
        Task task = new Task();
        task.setTid(taskId);
        task.setStatus(TaskStatus.RUNNING);
        task.setStartTime(startTime);
        task.setMaxRuntimeSeconds(maxRuntimeSeconds);
        return task;
    }
}
