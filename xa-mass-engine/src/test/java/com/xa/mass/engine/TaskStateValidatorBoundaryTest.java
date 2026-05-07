package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskStateValidatorBoundaryTest {

    @Test
    void runtimeValidationDoesNotTouchProjectionAuditReadSurface() {
        CountingStateRuntimePort runtime = new CountingStateRuntimePort(sampleTask(), TaskWorkStats.EMPTY);
        TrackingTaskDetailStore detailStore = new TrackingTaskDetailStore();
        TaskStateValidator validator = new TaskStateValidator(runtime, detailStore, new com.xa.mass.engine.util.TraceEventLogger(null));

        TaskStateValidationResult result = validator.validateTaskState("task-1");

        assertTrue(result.isValid());
        assertEquals(TaskStateValidationResult.Scope.RUNTIME, result.getScope());
        assertEquals(0, detailStore.projectionAuditReads.get());
    }

    @Test
    void explicitProjectionAuditUsesProjectionReadSurface() {
        TaskMsg msg = new TaskMsg("msg-1", "task-1", Map.of("target", "alpha"));
        CountingStateRuntimePort runtime = new CountingStateRuntimePort(sampleTask(), TaskWorkStats.EMPTY);
        TrackingTaskDetailStore detailStore = new TrackingTaskDetailStore();
        detailStore.addTaskMessage("task-1", msg);
        TaskStateValidator validator = new TaskStateValidator(runtime, detailStore, new com.xa.mass.engine.util.TraceEventLogger(null));

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

        @Override
        public TaskDetailStore.TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId) {
            return new TaskDetailStore.TaskMessageAttemptStats(0, 0, 0, 0, 0);
        }
    }

    private static final class TrackingTaskDetailStore extends InMemoryTaskStorage {
        private final AtomicInteger projectionAuditReads = new AtomicInteger();

        @Override
        public List<TaskMsg> getTaskMessages(String taskId) {
            projectionAuditReads.incrementAndGet();
            return super.getTaskMessages(taskId);
        }
    }
}
