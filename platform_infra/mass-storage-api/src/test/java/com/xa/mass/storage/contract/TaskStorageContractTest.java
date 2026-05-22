package com.xa.mass.storage.contract;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.base.project.ProjectRegistry;
import com.xa.mass.storage.api.TaskStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural contract for {@link TaskStorage} implementations.
 *
 * <p>Key invariants guarded here:
 * <ul>
 *   <li>Missing-key queries return empty, never null</li>
 *   <li>Status filter is exact: READY task is not returned by getTasksByStatus(RUNNING)</li>
 *   <li>getSchedulableTasks respects both status == READY AND taskNonSuccessNumber &gt; 0</li>
 *   <li>pollExpiredMaxRuntimeTasks only returns tasks past their deadline</li>
 * </ul>
 */
public abstract class TaskStorageContractTest {

    protected TaskStorage storage;

    protected abstract TaskStorage createStorage();

    protected void destroyStorage(TaskStorage storage) {
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

    // ── missing-key safety ────────────────────────────────────────────────────

    @Test
    void getTask_returnsEmpty_whenNotPresent() {
        assertThat(storage.getTask("no-such-task")).isEmpty();
    }

    @Test
    void deleteTask_returnsFalse_whenNotPresent() {
        assertThat(storage.deleteTask("ghost")).isFalse();
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

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

    // ── status filter ─────────────────────────────────────────────────────────

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

    // ── project filter ────────────────────────────────────────────────────────

    @Test
    void getTasksByProject_returnsOnlyMatchingProject() {
        storage.saveTask(readyTask("t-a", "proj-a"));
        storage.saveTask(readyTask("t-b", "proj-b"));
        assertThat(storage.getTasksByProject("proj-a"))
                .extracting(Task::getTid).containsExactly("t-a");
    }

    // ── schedulable ───────────────────────────────────────────────────────────

    @Test
    void getSchedulableTasks_returnsReadyTasksWithNonZeroWorkRemaining() {
        Task schedulable = readyTask("t-sched", "proj-a");  // READY + taskNonSuccessNumber > 0
        storage.saveTask(schedulable);

        Task noWork = new Task("t-no-work", "name", "proj-a", 0, Map.of(), UserRef.of("u"));
        noWork.setStatus(TaskStatus.READY);
        noWork.setTaskNonSuccessNumber(0);
        storage.saveTask(noWork);

        Task running = readyTask("t-running", "proj-a");
        running.setStatus(TaskStatus.RUNNING);
        storage.saveTask(running);

        assertThat(storage.getSchedulableTasks())
                .extracting(Task::getTid).containsExactly("t-sched");
    }

    // ── max-runtime expiry ────────────────────────────────────────────────────

    @Test
    void pollExpiredMaxRuntimeTasks_returnsOnlyExpiredOnes() {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 12, 0, 0);

        Task expired = readyTask("t-expired", "proj-a");
        expired.setStatus(TaskStatus.RUNNING);
        expired.getExecutionSpec().setMaxRuntimeSeconds(60);
        expired.setStartTime(now.minusSeconds(120));  // started 2 min ago, limit 1 min → expired
        storage.saveTask(expired);

        Task notYet = readyTask("t-not-yet", "proj-a");
        notYet.setStatus(TaskStatus.RUNNING);
        notYet.getExecutionSpec().setMaxRuntimeSeconds(600);
        notYet.setStartTime(now.minusSeconds(30));  // started 30s ago, limit 10 min → ok
        storage.saveTask(notYet);

        Task noLimit = readyTask("t-no-limit", "proj-a");
        noLimit.setStatus(TaskStatus.RUNNING);
        noLimit.getExecutionSpec().setMaxRuntimeSeconds(0);  // 0 = unlimited
        noLimit.setStartTime(now.minusSeconds(9999));
        storage.saveTask(noLimit);

        assertThat(storage.pollExpiredMaxRuntimeTasks(now, 10))
                .extracting(Task::getTid).containsExactly("t-expired");
    }

    @Test
    void pollExpiredMaxRuntimeTasks_respectsLimit() {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        for (int i = 0; i < 5; i++) {
            Task task = readyTask("t-expired-" + i, "proj-a");
            task.setStatus(TaskStatus.RUNNING);
            task.getExecutionSpec().setMaxRuntimeSeconds(10);
            task.setStartTime(now.minusSeconds(60));
            storage.saveTask(task);
        }
        assertThat(storage.pollExpiredMaxRuntimeTasks(now, 3)).hasSize(3);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    protected Task readyTask(String taskId, String project) {
        Task task = new Task(taskId, "name-" + taskId, project, 1, Map.of(), UserRef.of("u"));
        task.setStatus(TaskStatus.READY);
        return task;
    }
}
