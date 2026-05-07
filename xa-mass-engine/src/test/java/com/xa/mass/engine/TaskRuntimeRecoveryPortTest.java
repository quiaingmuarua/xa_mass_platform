package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskCreateRequestDto;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.TaskWorkClaimOptions;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.runtime.api.TaskWorkResult;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.api.TaskWorkRuntimeStats;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.api.WorkEnqueueOptions;
import com.xa.mass.runtime.api.WorkEnqueueOutcome;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskRuntimeRecoveryPortTest {

    @Test
    void runtimeRecoveryOnlyReturnsTasksAdvertisedByRuntimeReadySet() {
        SimpleTaskScheduler scheduler = new SimpleTaskScheduler();
        InMemoryTaskStorage storage = new InMemoryTaskStorage();
        ReadyTaskIdsOverrideRuntime runtime = new ReadyTaskIdsOverrideRuntime();
        TaskManager manager = new TaskManager(scheduler, storage, storage, runtime);

        Task first = manager.createTask(buildRequest("runtime-ready-first"));
        Task second = manager.createTask(buildRequest("runtime-ready-second"));
        manager.approveTask(first.getTid());
        manager.approveTask(second.getTid());
        runtime.setReadyTaskIds(List.of(second.getTid()));

        TaskRuntimeRecoveryPort recoveryPort = new TaskManagerRuntimeRecoveryPort(manager);
        List<Task> recovered = recoveryPort.getRuntimeDispatchableTasks(10);

        assertEquals(List.of(second.getTid()), recovered.stream().map(Task::getTid).toList());
    }

    @Test
    void runtimeRecoveryDropsRuntimeResidueThatNoLongerHasTaskShellTruth() {
        SimpleTaskScheduler scheduler = new SimpleTaskScheduler();
        InMemoryTaskStorage storage = new InMemoryTaskStorage();
        ReadyTaskIdsOverrideRuntime runtime = new ReadyTaskIdsOverrideRuntime();
        TaskManager manager = new TaskManager(scheduler, storage, storage, runtime);

        Task task = manager.createTask(buildRequest("runtime-ready-live"));
        manager.approveTask(task.getTid());
        runtime.setReadyTaskIds(List.of(task.getTid(), "missing-task-shell"));

        TaskRuntimeRecoveryPort recoveryPort = new TaskManagerRuntimeRecoveryPort(manager);
        List<Task> recovered = recoveryPort.getRuntimeDispatchableTasks(10);

        assertEquals(List.of(task.getTid()), recovered.stream().map(Task::getTid).toList());
    }

    private static TaskCreateRequestDto buildRequest(String taskName) {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName(taskName);
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setInputs(List.of(
                java.util.Map.of("target", taskName + "-a"),
                java.util.Map.of("target", taskName + "-b")
        ));
        return dto;
    }

    private static final class ReadyTaskIdsOverrideRuntime implements TaskWorkRuntime {

        private final InMemoryTaskWorkRuntime delegate = new InMemoryTaskWorkRuntime();
        private volatile List<String> readyTaskIds = List.of();

        void setReadyTaskIds(List<String> readyTaskIds) {
            this.readyTaskIds = List.copyOf(readyTaskIds);
        }

        @Override
        public WorkEnqueueOutcome enqueue(TaskWorkEnvelope item, WorkEnqueueOptions options) {
            return delegate.enqueue(item, options);
        }

        @Override
        public List<String> readyTaskIds(int limit) {
            if (limit <= 0) {
                return List.of();
            }
            return readyTaskIds.stream().limit(limit).toList();
        }

        @Override
        public List<ClaimedTaskWork> claimReady(String taskId,
                                                List<WorkerClaimTarget> workers,
                                                TaskWorkClaimOptions options) {
            return delegate.claimReady(taskId, workers, options);
        }

        @Override
        public ResultApplyOutcome applyResult(TaskWorkResult result) {
            return delegate.applyResult(result);
        }

        @Override
        public List<ActiveLeaseRecord> pollExpiredLeases(int limit, Instant now) {
            return delegate.pollExpiredLeases(limit, now);
        }

        @Override
        public List<ActiveLeaseRecord> activeLeases(String taskId) {
            return delegate.activeLeases(taskId);
        }

        @Override
        public Optional<ActiveLeaseRecord> getActiveLease(String taskId, String messageId) {
            return delegate.getActiveLease(taskId, messageId);
        }

        @Override
        public boolean hasReadyWork(String taskId) {
            return delegate.hasReadyWork(taskId);
        }

        @Override
        public boolean hasActiveLeaseForWorker(String taskId, String workerId) {
            return delegate.hasActiveLeaseForWorker(taskId, workerId);
        }

        @Override
        public TaskWorkStats stats(String taskId) {
            return delegate.stats(taskId);
        }

        @Override
        public TaskWorkRuntimeStats stats() {
            return delegate.stats();
        }

        @Override
        public long discardTask(String taskId) {
            return delegate.discardTask(taskId);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }
    }
}
