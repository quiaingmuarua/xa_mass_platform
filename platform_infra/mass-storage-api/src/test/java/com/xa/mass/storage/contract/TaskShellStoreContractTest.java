package com.xa.mass.storage.contract;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.base.project.ProjectRegistry;
import com.xa.mass.storage.api.TaskShellStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural contract for {@link TaskShellStore} implementations.
 */
public abstract class TaskShellStoreContractTest {

    protected TaskShellStore storage;

    protected abstract TaskShellStore createStorage();

    protected void destroyStorage(TaskShellStore storage) {
    }

    @BeforeEach
    void setUp() {
        ProjectRegistry.register("proj-a", "Project A");
        ProjectRegistry.register("proj-b", "Project B");
        storage = createStorage();
    }

    @AfterEach
    void tearDown() {
        destroyStorage(storage);
    }

    @Test
    void getTask_returnsEmpty_whenNotPresent() {
        assertThat(storage.getTask("no-such-task")).isEmpty();
    }

    @Test
    void deleteTask_returnsFalse_whenNotPresent() {
        assertThat(storage.deleteTask("ghost")).isFalse();
    }

    @Test
    void saveAndGetTask_persists() {
        storage.saveTask(readyTask("t1", "proj-a"));
        assertThat(storage.getTask("t1")).isPresent()
                .get().extracting(Task::getProject).isEqualTo("proj-a");
    }

    @Test
    void updateTask_persistsChanges() {
        storage.saveTask(readyTask("t1", "proj-a"));
        Task task = storage.getTask("t1").orElseThrow();
        task.setStatus(TaskStatus.RUNNING);
        storage.updateTask(task);
        assertThat(storage.getTask("t1")).get()
                .extracting(Task::getStatus).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void deleteTask_removesTask() {
        storage.saveTask(readyTask("t1", "proj-a"));
        assertThat(storage.deleteTask("t1")).isTrue();
        assertThat(storage.getTask("t1")).isEmpty();
    }

    @Test
    void getTasksByStatus_returnsOnlyMatchingStatus() {
        storage.saveTask(readyTask("t-ready", "proj-a"));
        Task running = readyTask("t-running", "proj-a");
        running.setStatus(TaskStatus.RUNNING);
        storage.saveTask(running);

        assertThat(storage.getTasksByStatus(TaskStatus.READY))
                .extracting(Task::getTid).containsExactly("t-ready");
        assertThat(storage.getTasksByStatus(TaskStatus.RUNNING))
                .extracting(Task::getTid).containsExactly("t-running");
    }

    @Test
    void getTasksByStatus_returnsEmpty_whenNoneMatch() {
        storage.saveTask(readyTask("t1", "proj-a"));
        assertThat(storage.getTasksByStatus(TaskStatus.TERMINAL)).isEmpty();
    }

    @Test
    void getTasksByProject_returnsOnlyMatchingProject() {
        storage.saveTask(readyTask("t-a", "proj-a"));
        storage.saveTask(readyTask("t-b", "proj-b"));
        assertThat(storage.getTasksByProject("proj-a"))
                .extracting(Task::getTid).containsExactly("t-a");
    }

    protected Task readyTask(String taskId, String project) {
        Task task = new Task(taskId, "name-" + taskId, project, 1, Map.of(), UserRef.of("u"));
        task.setStatus(TaskStatus.READY);
        return task;
    }
}
