package com.xa.mass.storage.memory;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTaskShellStoreIndexTest {

    @Test
    void pollTasksPastMaxRuntimeDeadlineUsesDeadlineIndex() {
        InMemoryTaskShellStore storage = new InMemoryTaskShellStore();
        LocalDateTime now = LocalDateTime.now();
        Task expired = runningTask("expired", now.minusSeconds(20), 10);
        Task future = runningTask("future", now.minusSeconds(5), 60);
        Task unlimited = runningTask("unlimited", now.minusSeconds(100), 0);
        storage.saveTask(expired);
        storage.saveTask(future);
        storage.saveTask(unlimited);

        List<Task> tasks = storage.pollTasksPastMaxRuntimeDeadline(now, 10);

        assertEquals(List.of("expired"), tasks.stream().map(Task::getTid).toList());
        assertTrue(storage.pollTasksPastMaxRuntimeDeadline(now.plusSeconds(1), 10).isEmpty());
    }

    @Test
    void updateTaskRefreshesMaxRuntimeDeadlineAfterInPlaceMutation() {
        InMemoryTaskShellStore storage = new InMemoryTaskShellStore();
        LocalDateTime now = LocalDateTime.now();
        Task task = runningTask("mutable", now, 60);
        storage.saveTask(task);

        Task stored = storage.getTask("mutable").orElseThrow();
        stored.setStartTime(now.minusSeconds(120));
        assertTrue(storage.updateTask(stored));

        List<Task> tasks = storage.pollTasksPastMaxRuntimeDeadline(now, 10);

        assertEquals(List.of("mutable"), tasks.stream().map(Task::getTid).toList());
    }

    @Test
    void updateTaskRefreshesProjectIndexAfterInPlaceMutation() {
        InMemoryTaskShellStore storage = new InMemoryTaskShellStore();
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
        InMemoryTaskShellStore storage = new InMemoryTaskShellStore();
        LocalDateTime now = LocalDateTime.now();
        Task task = runningTask("terminal", now.minusSeconds(120), 60);
        storage.saveTask(task);

        task.setStatus(TaskStatus.TERMINAL);
        assertTrue(storage.updateTask(task));

        assertTrue(storage.pollTasksPastMaxRuntimeDeadline(now, 10).isEmpty());
    }

    @Test
    void deleteTaskRemovesShellAndDeadlineIndex() {
        InMemoryTaskShellStore storage = new InMemoryTaskShellStore();
        storage.saveTask(runningTask("task-1", LocalDateTime.now(), 60));

        assertTrue(storage.deleteTask("task-1"));
        assertTrue(storage.getTask("task-1").isEmpty());
        assertTrue(storage.pollTasksPastMaxRuntimeDeadline(LocalDateTime.now().plusSeconds(120), 10).isEmpty());
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
