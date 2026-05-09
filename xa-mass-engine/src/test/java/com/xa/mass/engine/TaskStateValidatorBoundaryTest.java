package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskStateValidatorBoundaryTest {

    @Test
    void runtimeValidationDoesNotTouchProjectionAuditReadSurface() {
        CountingStateRuntimePort runtime = new CountingStateRuntimePort(sampleTask(), TaskWorkStats.EMPTY);
        TrackingTaskDetailStore detailStore = new TrackingTaskDetailStore();
        TaskStateValidator validator = new TaskStateValidator(
                runtime,
                detailStore,
                new com.xa.mass.engine.util.TraceEventLogger(null)
        );

        TaskStateValidationResult result = validator.validateTaskState("task-1");

        assertTrue(result.isValid());
        assertEquals(TaskStateValidationResult.Scope.RUNTIME, result.getScope());
        assertEquals(0, detailStore.projectionAuditReads.get());
    }

    @Test
    void explicitProjectionAuditUsesProjectionReadSurface() {
        CountingStateRuntimePort runtime = new CountingStateRuntimePort(sampleTask(), TaskWorkStats.EMPTY);
        TrackingTaskDetailStore detailStore = new TrackingTaskDetailStore();
        detailStore.upsertTaskMessageProjection(
                "task-1",
                new TaskDetailStore.TaskMessageProjection(
                        "msg-1",
                        "task-1",
                        Map.of("target", "alpha"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        3,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        );
        TaskStateValidator validator = new TaskStateValidator(
                runtime,
                detailStore,
                new com.xa.mass.engine.util.TraceEventLogger(null)
        );

        TaskStateValidationResult result = validator.auditTaskProjectionState("task-1");

        assertTrue(result.isValid());
        assertEquals(TaskStateValidationResult.Scope.PROJECTION_AUDIT, result.getScope());
        assertEquals(1, detailStore.projectionAuditReads.get());
    }

    private static Task sampleTask() {
        Task task = new Task("task-1", "demo-task", "demoApp", 0, Map.of(), null);
        task.setStatus(TaskStatus.READY);
        task.setTaskEligibleNumber(0);
        task.setTaskSuccessNumber(0);
        return task;
    }

    private static final class CountingStateRuntimePort implements TaskStateRuntimePort {

        private final Task task;
        private final TaskWorkStats stats;

        private CountingStateRuntimePort(Task task,
                                         TaskWorkStats stats) {
            this.task = task;
            this.stats = stats;
        }

        @Override
        public Task getTask(String taskId) {
            return task;
        }

        @Override
        public TaskWorkStats getTaskWorkStats(String taskId) {
            return stats;
        }

        @Override
        public TaskTerminalPolicyDecision evaluateTerminalPolicy(Task task, TaskWorkStats stats) {
            return TaskTerminalPolicyDecision.keepRunning();
        }

    }

    private static final class TrackingTaskDetailStore extends InMemoryTaskStorage {
        private final AtomicInteger projectionAuditReads = new AtomicInteger();

        @Override
        public TaskDetailStore.TaskMessageStats getTaskMessageStats(String taskId) {
            projectionAuditReads.incrementAndGet();
            return super.getTaskMessageStats(taskId);
        }
    }
}
