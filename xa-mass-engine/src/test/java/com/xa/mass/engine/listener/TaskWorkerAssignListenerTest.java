package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.TaskAssignmentEventSink;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.engine.testutil.RecordingEventSink;
import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventType;
import com.xa.mass.worker.runtime.selection.SelectedWorkerHandle;
import com.xa.mass.worker.runtime.selection.WorkerSelectionRequest;
import com.xa.mass.worker.runtime.selection.WorkerSelectionResult;
import com.xa.mass.worker.runtime.selection.WorkerSelectionRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TaskWorkerAssignListenerTest {

    private WorkerSelectionRuntime workerSelectionRuntime;
    private TaskDispatchBinder dispatchBinder;
    private TaskAssignmentRuntimePort assignmentRuntime;
    private TaskAssignmentEventSink assignmentEvents;
    private TaskWorkerAssignListener listener;

    @BeforeEach
    void setUp() {
        workerSelectionRuntime = mock(WorkerSelectionRuntime.class);
        dispatchBinder = mock(TaskDispatchBinder.class);
        assignmentRuntime = mock(TaskAssignmentRuntimePort.class);
        assignmentEvents = mock(TaskAssignmentEventSink.class);
        listener = new TaskWorkerAssignListener(
                workerSelectionRuntime,
                dispatchBinder,
                assignmentRuntime,
                assignmentEvents,
                TraceEventLogger.noop()
        );
    }

    @Test
    void selectsWorkersThroughWorkerRuntimeAndDispatchesOnlySelectedHandles() {
        Task task = task("task-1", TaskStatus.READY, 2, 1);
        SelectedWorkerHandle first = handle("worker-1", task.getTid(), true);
        SelectedWorkerHandle second = handle("worker-2", task.getTid(), true);
        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(2);
        when(assignmentRuntime.countActiveDispatchWorkers(task.getTid())).thenReturn(0);
        when(workerSelectionRuntime.selectAndReserve(any(WorkerSelectionRequest.class)))
                .thenReturn(selection(first, second));
        when(dispatchBinder.bindDispatches(task, List.of(first)))
                .thenReturn(List.of(binding(task.getTid(), "worker-1")));
        when(assignmentRuntime.persistAssignmentState(task)).thenReturn(true);

        assertTrue(listener.onTaskAssign(task));

        ArgumentCaptor<WorkerSelectionRequest> request = ArgumentCaptor.forClass(WorkerSelectionRequest.class);
        verify(workerSelectionRuntime).selectAndReserve(request.capture());
        assertEquals(task.getTid(), request.getValue().selectionScopeKey());
        assertEquals(List.of("pool-a"), request.getValue().intent().workerGroupIds());
        assertEquals("us", request.getValue().intent().routingCode());
        assertTrue(request.getValue().exclusiveWorkerLock());
        verify(dispatchBinder).bindDispatches(task, List.of(first));
        verify(workerSelectionRuntime).releaseSelected(second);
        verify(assignmentEvents).publishTaskAssigned(task);
        assertEquals(TaskStatus.RUNNING, task.getStatus());
    }

    @Test
    void skipsDispatchWhenWorkerRuntimeReturnsNoSelectedWorkers() {
        Task task = task("task-no-worker", TaskStatus.READY, 1, 1);
        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(1);
        when(workerSelectionRuntime.selectAndReserve(any(WorkerSelectionRequest.class)))
                .thenReturn(WorkerSelectionResult.empty(1));

        assertFalse(listener.onTaskAssign(task));

        verify(dispatchBinder, never()).bindDispatches(any(), any());
        verify(assignmentEvents, never()).publishTaskAssigned(any());
        assertEquals(TaskStatus.READY, task.getStatus());
    }

    @Test
    void releasesSelectionWhenReadyTaskDoesNotMeetMinimumStartGate() {
        Task task = task("task-min-gate", TaskStatus.READY, 1, 2);
        SelectedWorkerHandle selected = handle("worker-1", task.getTid(), true);
        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(1);
        when(workerSelectionRuntime.selectAndReserve(any(WorkerSelectionRequest.class)))
                .thenReturn(selection(selected));

        assertFalse(listener.onTaskAssign(task));

        verify(workerSelectionRuntime).releaseSelected(selected);
        verify(dispatchBinder, never()).bindDispatches(any(), any());
        assertEquals(TaskStatus.READY, task.getStatus());
    }

    @Test
    void releasesDispatchCandidateLocksWhenBinderProducesNoBoundWork() {
        Task task = task("task-empty-bind", TaskStatus.READY, 1, 1);
        SelectedWorkerHandle selected = handle("worker-1", task.getTid(), true);
        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(1);
        when(workerSelectionRuntime.selectAndReserve(any(WorkerSelectionRequest.class)))
                .thenReturn(selection(selected));
        when(dispatchBinder.bindDispatches(task, List.of(selected))).thenReturn(List.of());

        assertFalse(listener.onTaskAssign(task));

        verify(workerSelectionRuntime).releaseSelectedLock(selected);
        verify(assignmentEvents, never()).publishTaskAssigned(any());
    }

    @Test
    void emitsAcceptedWorkerMatchTraceForSelectedWorkers() {
        RecordingEventSink sink = new RecordingEventSink();
        listener = new TaskWorkerAssignListener(
                workerSelectionRuntime,
                dispatchBinder,
                assignmentRuntime,
                assignmentEvents,
                new TraceEventLogger(sink)
        );
        Task task = task("task-trace-match", TaskStatus.READY, 1, 1);
        SelectedWorkerHandle selected = handle("worker-1", task.getTid(), true);
        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(1);
        when(workerSelectionRuntime.selectAndReserve(any(WorkerSelectionRequest.class)))
                .thenReturn(selection(selected));
        when(dispatchBinder.bindDispatches(task, List.of(selected)))
                .thenReturn(List.of(binding(task.getTid(), "worker-1")));
        when(assignmentRuntime.persistAssignmentState(task)).thenReturn(true);

        assertTrue(listener.onTaskAssign(task));

        ExecutionEvent event = sink.firstEventOfType(ExecutionEventType.WORKER_MATCH_ACCEPTED).orElseThrow();
        assertEquals(task.getTid(), event.getIdentity().taskId());
        assertEquals("worker-1", event.getIdentity().workerId());
        assertEquals("pool-a", event.getAttrs().get("workerGroupId"));
        assertEquals(1, event.getAttrs().get("candidateRank"));
    }

    private static WorkerSelectionResult selection(SelectedWorkerHandle... handles) {
        return new WorkerSelectionResult(List.of(handles), handles.length, 0, Map.of());
    }

    private static SelectedWorkerHandle handle(String workerId, String taskId, boolean exclusiveWorkerLock) {
        return SelectedWorkerHandle.of(
                workerId,
                "pool-a",
                taskId,
                exclusiveWorkerLock
        );
    }

    private static TaskDispatchBinding binding(String taskId, String workerId) {
        return TaskDispatchBinding.workerLevelWithEvidence(
                taskId,
                "message-1",
                "demo.event",
                Map.of("payload", "hello"),
                null,
                0,
                "attempt-1",
                1,
                "lease-1",
                workerId,
                "batch-1",
                "pool-a",
                null,
                null,
                "demoApp:demo.event",
                "GROUP_SELECTOR"
        );
    }

    private static Task task(String taskId, TaskStatus status, int readyBatchSize, int minRequiredWorkerCount) {
        Task task = new Task();
        task.setTid(taskId);
        task.setProject("demoApp");
        task.setStatus(status);
        task.setMinRequiredWorkerCount(minRequiredWorkerCount);
        task.setSharedConfig(Map.of(
                TaskSharedConfig.WORKER_GROUP_ID, "pool-a",
                TaskSharedConfig.ROUTING_CODE, "us"
        ));
        TaskExecutionSpec executionSpec = new TaskExecutionSpec();
        executionSpec.setBatchSize(readyBatchSize);
        executionSpec.setForeground(true);
        task.setExecutionSpec(executionSpec);
        return task;
    }
}
