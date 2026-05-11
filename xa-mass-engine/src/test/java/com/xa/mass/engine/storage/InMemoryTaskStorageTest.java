package com.xa.mass.engine.storage;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;
import com.xa.mass.storage.api.projection.TaskMessageProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;
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

        TaskDetailStore.TaskMessageAttemptProjection runningAttempt =
                attempt("attempt-1", 1, TaskMessageAttemptProjectionStatus.RUNNING);
        TaskDetailStore.TaskMessageAttemptProjection failedAttempt =
                attempt("attempt-2", 2, TaskMessageAttemptProjectionStatus.FAILED);

        storage.upsertTaskMessageAttemptProjection("task-1", "msg-1", runningAttempt);
        storage.upsertTaskMessageAttemptProjection("task-1", "msg-1", failedAttempt);

        Optional<TaskDetailStore.TaskMessageAttemptProjection> latestActive =
                storage.getLatestActiveTaskMessageAttemptProjection("task-1", "msg-1");

        assertTrue(latestActive.isPresent());
        assertEquals("attempt-1", latestActive.get().attemptId());
    }

    @Test
    void perMessageAttemptStatsTrackIndexedAttemptStateCounts() {
        InMemoryTaskStorage storage = new InMemoryTaskStorage();

        TaskDetailStore.TaskMessageAttemptProjection runningAttempt =
                attempt("attempt-1", 1, TaskMessageAttemptProjectionStatus.RUNNING);
        TaskDetailStore.TaskMessageAttemptProjection failedAttempt =
                attempt("attempt-2", 2, TaskMessageAttemptProjectionStatus.FAILED);

        storage.upsertTaskMessageAttemptProjection("task-1", "msg-1", runningAttempt);
        storage.upsertTaskMessageAttemptProjection("task-1", "msg-1", failedAttempt);

        TaskDetailStore.TaskMessageAttemptStats initialStats = storage.getTaskMessageAttemptStats("task-1", "msg-1");
        assertEquals(2, initialStats.getTotalAttempts());
        assertEquals(1, initialStats.getActiveAttempts());
        assertEquals(1, initialStats.getRunningAttempts());
        assertEquals(1, initialStats.getFailedAttempts());
        assertEquals(0, initialStats.getExpiredAttempts());

        runningAttempt = new TaskDetailStore.TaskMessageAttemptProjection(
                runningAttempt.attemptId(),
                runningAttempt.taskId(),
                runningAttempt.messageId(),
                runningAttempt.attemptNo(),
                runningAttempt.workerId(),
                runningAttempt.workerContextId(),
                runningAttempt.batchId(),
                TaskMessageAttemptProjectionStatus.SUCCEEDED,
                TaskMessageAttemptProjectionFinalReason.SUCCESS,
                runningAttempt.errorMessage(),
                runningAttempt.errorCode(),
                runningAttempt.output()
        );
        assertTrue(storage.upsertTaskMessageAttemptProjection("task-1", "msg-1", runningAttempt));

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

        TaskDetailStore.TaskMessageProjection init = message("msg-init", TaskMessageProjectionStatus.INIT, null);
        TaskDetailStore.TaskMessageProjection assigned = message("msg-assigned", TaskMessageProjectionStatus.ASSIGNED, null);
        TaskDetailStore.TaskMessageProjection success = message(
                "msg-success",
                TaskMessageProjectionStatus.SUCCESS,
                TaskMessageProjectionFinalReason.BUSINESS_SUCCESS
        );
        TaskDetailStore.TaskMessageProjection failed = message(
                "msg-failed",
                TaskMessageProjectionStatus.FAILED,
                TaskMessageProjectionFinalReason.MANUAL_CANCELLED
        );

        storage.upsertTaskMessageProjection("task-1", init);
        storage.upsertTaskMessageProjection("task-1", assigned);
        storage.upsertTaskMessageProjection("task-1", success);
        storage.upsertTaskMessageProjection("task-1", failed);

        assertEquals(java.util.Set.of("msg-init", "msg-assigned"),
                allMessageProjections(storage, "task-1").stream()
                        .filter(projection -> projection.status() == null || !projection.status().isFinal())
                        .map(TaskDetailStore.TaskMessageProjection::messageId)
                        .collect(java.util.stream.Collectors.toSet()));

        assigned = new TaskDetailStore.TaskMessageProjection(
                assigned.messageId(),
                assigned.taskId(),
                assigned.input(),
                assigned.payloadRef(),
                TaskMessageProjectionStatus.EXPIRED,
                assigned.assignedTime(),
                assigned.createTime(),
                LocalDateTime.now(),
                assigned.startTime(),
                LocalDateTime.now(),
                assigned.retryCount(),
                assigned.maxRetryCount(),
                assigned.errorMessage(),
                assigned.errorCode(),
                TaskMessageProjectionFinalReason.MANUAL_CANCELLED,
                null,
                assigned.latestAttemptId(),
                assigned.latestAttemptWorkerId(),
                assigned.latestAttemptWorkerContextId(),
                assigned.latestAttemptBatchId()
        );
        assertTrue(storage.upsertTaskMessageProjection("task-1", assigned));

        assertEquals(java.util.Set.of("msg-init"),
                allMessageProjections(storage, "task-1").stream()
                        .filter(projection -> projection.status() == null || !projection.status().isFinal())
                        .map(TaskDetailStore.TaskMessageProjection::messageId)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void deleteTaskReleasesMessageBucketsAttemptsAndPendingIndex() {
        InMemoryTaskStorage storage = new InMemoryTaskStorage();
        storage.saveTask(runningTask("task-1", LocalDateTime.now(), 60));

        TaskDetailStore.TaskMessageProjection init = message("msg-init", TaskMessageProjectionStatus.INIT, null);
        storage.upsertTaskMessageProjection("task-1", init);
        storage.upsertTaskMessageAttemptProjection("task-1", "msg-init",
                attempt("attempt-1", 1, TaskMessageAttemptProjectionStatus.RUNNING));

        assertTrue(storage.deleteTask("task-1"));
        assertTrue(storage.getTask("task-1").isEmpty());
        assertTrue(allMessageProjections(storage, "task-1").isEmpty());
        assertEquals(0, storage.getTaskMessageStats("task-1").getTotal());
        assertTrue(storage.getTaskMessageAttemptProjections("task-1", "msg-init").isEmpty());
    }

    @Test
    void saveTaskDoesNotResetExistingMessageAndAttemptBuckets() {
        InMemoryTaskStorage storage = new InMemoryTaskStorage();
        storage.saveTask(runningTask("task-1", LocalDateTime.now(), 60));

        TaskDetailStore.TaskMessageProjection init = message("msg-init", TaskMessageProjectionStatus.INIT, null);
        storage.upsertTaskMessageProjection("task-1", init);
        storage.upsertTaskMessageAttemptProjection("task-1", "msg-init",
                attempt("attempt-1", 1, TaskMessageAttemptProjectionStatus.RUNNING));

        Task replacement = runningTask("task-1", LocalDateTime.now(), 120);
        storage.saveTask(replacement);

        assertEquals(1, storage.getTaskMessageStats("task-1").getTotal());
        assertEquals(List.of("msg-init"),
                allMessageProjections(storage, "task-1").stream().map(TaskDetailStore.TaskMessageProjection::messageId).toList());
        assertEquals(1, storage.getTaskMessageAttemptProjections("task-1", "msg-init").size());
    }

    private List<TaskDetailStore.TaskMessageProjection> allMessageProjections(InMemoryTaskStorage storage,
                                                                              String taskId) {
        long total = storage.getTaskMessageStats(taskId).getTotal();
        if (total <= 0) {
            return List.of();
        }
        return storage.getTaskMessageProjections(taskId, Math.toIntExact(total));
    }

    private TaskDetailStore.TaskMessageAttemptProjection attempt(String attemptId,
                                                                 int attemptNo,
                                                                 TaskMessageAttemptProjectionStatus status) {
        return new TaskDetailStore.TaskMessageAttemptProjection(
                attemptId,
                "task-1",
                "msg-1",
                attemptNo,
                null,
                null,
                null,
                status,
                null,
                null,
                null,
                null
        );
    }

    private TaskDetailStore.TaskMessageProjection message(String messageId,
                                                          TaskMessageProjectionStatus status,
                                                          TaskMessageProjectionFinalReason finalReason) {
        LocalDateTime now = LocalDateTime.now();
        return new TaskDetailStore.TaskMessageProjection(
                messageId,
                "task-1",
                java.util.Map.of(),
                null,
                status,
                status == TaskMessageProjectionStatus.ASSIGNED ? now : null,
                now,
                now,
                status == TaskMessageProjectionStatus.RUNNING || status == TaskMessageProjectionStatus.SUCCESS
                        || status == TaskMessageProjectionStatus.FAILED || status == TaskMessageProjectionStatus.EXPIRED
                        ? now
                        : null,
                status != null && status.isFinal() ? now : null,
                0,
                3,
                null,
                null,
                finalReason,
                null,
                null,
                null,
                null,
                null
        );
    }

    private Task runningTask(String taskId, LocalDateTime startTime, int maxRuntimeSeconds) {
        Task task = new Task();
        task.setTid(taskId);
        task.setStatus(TaskStatus.RUNNING);
        task.setStartTime(startTime);
        task.getExecutionSpec().setMaxRuntimeSeconds(maxRuntimeSeconds);
        return task;
    }

}
