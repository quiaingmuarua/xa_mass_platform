package com.xa.mass.storage.contract;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.base.project.ProjectRegistry;
import com.xa.mass.storage.api.TaskShellLifecycleQuery;
import com.xa.mass.storage.api.TaskShellStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural contract for current task-shell lifecycle query implementations.
 */
public abstract class TaskShellLifecycleQueryContractTest {

    protected TaskShellStore storage;
    protected TaskShellLifecycleQuery lifecycleQuery;

    protected abstract TaskShellStore createStorage();

    protected TaskShellLifecycleQuery createLifecycleQuery(TaskShellStore storage) {
        assertThat(storage).isInstanceOf(TaskShellLifecycleQuery.class);
        return (TaskShellLifecycleQuery) storage;
    }

    protected void destroyStorage(TaskShellStore storage) {
    }

    @BeforeEach
    void setUp() {
        ProjectRegistry.register("proj-a", "Project A");
        storage = createStorage();
        lifecycleQuery = createLifecycleQuery(storage);
    }

    @AfterEach
    void tearDown() {
        destroyStorage(storage);
    }

    @Test
    void pollTasksPastMaxRuntimeDeadline_returnsOnlyExpiredCurrentShells() {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 12, 0, 0);

        Task expired = readyTask("t-expired", "proj-a");
        expired.setStatus(TaskStatus.RUNNING);
        expired.getExecutionSpec().setMaxRuntimeSeconds(60);
        expired.setStartTime(now.minusSeconds(120));
        storage.saveTask(expired);

        Task notYet = readyTask("t-not-yet", "proj-a");
        notYet.setStatus(TaskStatus.RUNNING);
        notYet.getExecutionSpec().setMaxRuntimeSeconds(600);
        notYet.setStartTime(now.minusSeconds(30));
        storage.saveTask(notYet);

        Task noLimit = readyTask("t-no-limit", "proj-a");
        noLimit.setStatus(TaskStatus.RUNNING);
        noLimit.getExecutionSpec().setMaxRuntimeSeconds(0);
        noLimit.setStartTime(now.minusSeconds(9999));
        storage.saveTask(noLimit);

        assertThat(lifecycleQuery.pollTasksPastMaxRuntimeDeadline(now, 10))
                .extracting(Task::getTid).containsExactly("t-expired");
    }

    @Test
    void pollTasksPastMaxRuntimeDeadline_respectsLimit() {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        for (int i = 0; i < 5; i++) {
            Task task = readyTask("t-expired-" + i, "proj-a");
            task.setStatus(TaskStatus.RUNNING);
            task.getExecutionSpec().setMaxRuntimeSeconds(10);
            task.setStartTime(now.minusSeconds(60));
            storage.saveTask(task);
        }
        assertThat(lifecycleQuery.pollTasksPastMaxRuntimeDeadline(now, 3)).hasSize(3);
    }

    protected Task readyTask(String taskId, String project) {
        Task task = new Task(taskId, "name-" + taskId, project, 1, Map.of(), UserRef.of("u"));
        task.setStatus(TaskStatus.READY);
        return task;
    }
}
