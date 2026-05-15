package com.xa.mass.engine.resource;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.util.TraceEventLogCapture;
import com.xa.mass.engine.util.TraceEventLogger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyWorkerContextResourceLifecycleTest {

    @Test
    void prepareForDispatchMovesIdleContextToOccupiedAndPersistsOnce() {
        WorkerManager workerManager = mock(WorkerManager.class);
        WorkerContext workerContext = workerContext("ctx-1", "worker-1");
        Task task = task("task-1");
        LegacyWorkerContextResourceLifecycle lifecycle =
                new LegacyWorkerContextResourceLifecycle(workerManager, TraceEventLogger.noop());

        when(workerManager.updateWorkerContextById("ctx-1", workerContext)).thenReturn(true);

        assertTrue(lifecycle.prepareForDispatch(task, workerContext, "TestSource"));

        assertEquals(WorkerContextStatus.OCCUPIED, workerContext.getStatus());
        assertEquals("task-1", workerContext.getLastBindTaskId());
        verify(workerManager).updateWorkerContextById("ctx-1", workerContext);
    }

    @Test
    void prepareForDispatchRejectsBlockedContextWithoutPersistence() {
        WorkerManager workerManager = mock(WorkerManager.class);
        WorkerContext workerContext = workerContext("ctx-1", "worker-1");
        workerContext.block();
        Task task = task("task-1");
        LegacyWorkerContextResourceLifecycle lifecycle =
                new LegacyWorkerContextResourceLifecycle(workerManager, TraceEventLogger.noop());

        assertFalse(lifecycle.prepareForDispatch(task, workerContext, "TestSource"));

        verify(workerManager, never()).updateWorkerContextById("ctx-1", workerContext);
    }

    @Test
    void releaseIfOwnedByTaskMovesContextToIdleAndCanEmitResourceTrace() {
        WorkerManager workerManager = mock(WorkerManager.class);
        WorkerContext workerContext = workerContext("ctx-1", "worker-1");
        workerContext.bindToTask("task-1");
        workerContext.startOccupying();
        LegacyWorkerContextResourceLifecycle lifecycle =
                new LegacyWorkerContextResourceLifecycle(workerManager, TraceEventLogger.noop());

        when(workerManager.getWorkerContextById("ctx-1")).thenReturn(workerContext);
        when(workerManager.updateWorkerContextById("ctx-1", workerContext)).thenReturn(true);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            lifecycle.releaseIfOwnedByTask(
                    "task-1",
                    "worker-1",
                    "ctx-1",
                    "RELEASE_WORKER_CONTEXT",
                    "TestSource",
                    "test release",
                    true
            );

            capture.assertHasEvent("RESOURCE_RELEASED", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "worker-1".equals(mdc.get("workerId"))
                            && "ctx-1".equals(mdc.get("workerContextId")));
        }

        assertEquals(WorkerContextStatus.IDLE, workerContext.getStatus());
        assertNull(workerContext.getLastBindTaskId());
        verify(workerManager).updateWorkerContextById("ctx-1", workerContext);
    }

    @Test
    void releaseIfOwnedByTaskIgnoresContextOwnedByAnotherTask() {
        WorkerManager workerManager = mock(WorkerManager.class);
        WorkerContext workerContext = workerContext("ctx-1", "worker-1");
        workerContext.bindToTask("other-task");
        workerContext.startOccupying();
        LegacyWorkerContextResourceLifecycle lifecycle =
                new LegacyWorkerContextResourceLifecycle(workerManager, TraceEventLogger.noop());

        when(workerManager.getWorkerContextById("ctx-1")).thenReturn(workerContext);

        lifecycle.releaseIfOwnedByTask(
                "task-1",
                "worker-1",
                "ctx-1",
                "RELEASE_WORKER_CONTEXT",
                "TestSource",
                "test release",
                true
        );

        assertEquals(WorkerContextStatus.OCCUPIED, workerContext.getStatus());
        verify(workerManager, never()).updateWorkerContextById(same("ctx-1"), same(workerContext));
    }

    private Task task(String taskId) {
        Task task = new Task();
        task.setTid(taskId);
        return task;
    }

    private WorkerContext workerContext(String workerContextId, String workerId) {
        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId(workerContextId);
        workerContext.setWorkerId(workerId);
        return workerContext;
    }
}
