package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.TaskAssignmentEventSink;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.TaskEventPublisher;
import com.xa.mass.engine.TestWorkerCandidateRows;
import com.xa.mass.engine.worker.WorkerManager;
import com.xa.mass.runtime.worker.WorkerReachabilityState;
import com.xa.mass.runtime.worker.WorkerCandidateRow;
import com.xa.mass.runtime.worker.WorkerTaskSelector;
import com.xa.mass.engine.assignment.AssignmentAllocationDecision;
import com.xa.mass.engine.assignment.AssignmentAllocationOutcome;
import com.xa.mass.engine.assignment.AssignmentAllocationPlan;
import com.xa.mass.engine.assignment.AssignmentAllocationPolicy;
import com.xa.mass.engine.assignment.AssignmentAllocationRequest;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.model.WorkerSchedulingView;
import com.xa.mass.engine.resource.DefaultWorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourceUsage;
import com.xa.mass.engine.strategy.TaskWorkerMatchingStrategy;
import com.xa.mass.engine.util.TraceEventLogCapture;
import com.xa.mass.engine.util.TraceEventLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class TaskWorkerAssignListenerTest {

    private TaskWorkerMatchingStrategy matchingStrategy;
    private WorkerManager workerManager;
    private TaskDispatchBinder dispatchBinder;
    private TaskAssignmentRuntimePort assignmentRuntime;
    private TaskAssignmentEventSink assignmentEventSink;
    private TaskWorkerAssignListener listener;

    @BeforeEach
    void setUp() {
        matchingStrategy = mock(TaskWorkerMatchingStrategy.class);
        workerManager = mock(WorkerManager.class);
        dispatchBinder = mock(TaskDispatchBinder.class);
        assignmentRuntime = mock(TaskAssignmentRuntimePort.class);
        assignmentEventSink = new TaskEventPublisher();
        listener = new TaskWorkerAssignListener(
                matchingStrategy,
                workerManager,
                workerManager,
                dispatchBinder,
                assignmentRuntime,
                assignmentEventSink
        );
    }

    @Test
    void onTaskAssignTransitionsReadyTaskToRunningAndDispatches() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);
        Worker worker = createWorker("worker-1");
        WorkerSchedulingCandidate matchedWorker = matched(worker);

        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(10);
        when(matchingStrategy.matchWorkers(same(task), eq(2))).thenReturn(List.of(matchedWorker));
        when(dispatchBinder.bindDispatches(same(task), eq(List.of(matchedWorker)))).thenReturn(List.of(binding("m1", "worker-1")));

        assertTrue(listener.onTaskAssign(task));

        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals(1, task.getPeakAssignedWorkerCount());
        verify(matchingStrategy).matchWorkers(same(task), eq(2));
        verify(assignmentRuntime).updateTask(same(task));
        verify(dispatchBinder).bindDispatches(same(task), eq(List.of(matchedWorker)));
        verify(workerManager).recordWarmCandidate(
                argThat((WorkerTaskSelector selector) -> "task-1".equals(selector.taskId())),
                argThat((WorkerCandidateRow row) -> "worker-1".equals(row.workerId())));
    }

    @Test
    void onTaskAssignEmitsReadyToRunningTrace() {
        Task task = createTask(2, 2, 1, TaskStatus.READY);
        Worker worker = createWorker("worker-1");
        WorkerSchedulingCandidate matchedWorker = matched(worker);

        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(2);
        when(matchingStrategy.matchWorkers(same(task), eq(1))).thenReturn(List.of(matchedWorker));
        when(dispatchBinder.bindDispatches(same(task), eq(List.of(matchedWorker)))).thenReturn(List.of(binding("m1", "worker-1")));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(listener.onTaskAssign(task));
            capture.assertHasEvent("TASK_STATUS_TRANSITION", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "READY".equals(mdc.get("fromStatus"))
                            && "RUNNING".equals(mdc.get("toStatus"))
                            && "ASSIGNMENT_SUCCEEDED".equals(mdc.get("trigger")));
        }
    }

    @Test
    void onTaskAssignEmitsDispatchRequestedTrace() {
        Task task = createTask(2, 2, 1, TaskStatus.READY);
        Worker worker = createWorker("worker-1");
        WorkerSchedulingCandidate matchedWorker = matched(worker);

        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(2);
        when(matchingStrategy.matchWorkers(same(task), eq(1))).thenReturn(List.of(matchedWorker));
        when(dispatchBinder.bindDispatches(same(task), eq(List.of(matchedWorker)))).thenReturn(List.of(binding("m1", "worker-1")));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(listener.onTaskAssign(task));
            capture.assertHasEvent("DISPATCH_REQUESTED", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "ON_TASK_ASSIGN".equals(mdc.get("trigger"))
                            && "TaskWorkerAssignListener".equals(mdc.get("source")));
        }
    }

    @Test
    void onTaskAssignEmitsAssignmentSummary() {
        Task task = createTask(2, 2, 1, TaskStatus.READY);
        Worker worker = createWorker("worker-1");
        WorkerSchedulingCandidate matchedWorker = matched(worker);

        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(2);
        when(matchingStrategy.matchWorkers(same(task), eq(1))).thenReturn(List.of(matchedWorker));
        when(dispatchBinder.bindDispatches(same(task), eq(List.of(matchedWorker)))).thenReturn(List.of(binding("m1", "worker-1")));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(listener.onTaskAssign(task));
            capture.assertHasEvent("ASSIGNMENT_SUMMARY", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "READY".equals(mdc.get("initialStatus"))
                            && "RUNNING".equals(mdc.get("currentStatus"))
                            && "2".equals(mdc.get("pendingDispatchCount"))
                            && "1".equals(mdc.get("matchedWorkerCount"))
                            && "1".equals(mdc.get("dispatchCandidateCount"))
                            && "1".equals(mdc.get("dispatchedMessageCount"))
                            && "1".equals(mdc.get("usedWorkerCount"))
                            && "SUCCESS".equals(mdc.get("result")));
        }
    }

    @Test
    void onTaskAssignUsesMinRequiredWorkerCountWhenItExceedsCalculatedNeed() {
        Task task = createTask(3, 10, 4, TaskStatus.READY);
        java.util.Map<String, Object> sharedConfig = new java.util.HashMap<>(task.getSharedConfig());
        sharedConfig.put(TaskSharedConfig.WORKER_GROUP_ID, "group-a");
        task.setSharedConfig(sharedConfig);
        Worker worker1 = createWorker("worker-1");
        Worker worker2 = createWorker("worker-2");
        Worker worker3 = createWorker("worker-3");
        Worker worker4 = createWorker("worker-4");
        WorkerSchedulingCandidate matched1 = matched(worker1);
        WorkerSchedulingCandidate matched2 = matched(worker2);
        WorkerSchedulingCandidate matched3 = matched(worker3);
        WorkerSchedulingCandidate matched4 = matched(worker4);

        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(3);
        when(matchingStrategy.matchWorkers(same(task), eq(4))).thenReturn(List.of(matched1, matched2, matched3, matched4));
        when(dispatchBinder.bindDispatches(same(task), eq(List.of(matched1)))).thenReturn(List.of(binding("m1", "worker-1")));

        assertTrue(listener.onTaskAssign(task));

        verify(matchingStrategy).matchWorkers(same(task), eq(4));
        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals(1, task.getPeakAssignedWorkerCount());
        verify(assignmentRuntime).updateTask(same(task));
        verify(dispatchBinder).bindDispatches(same(task), eq(List.of(matched1)));
        verify(workerManager).releaseWorkerReservation("worker-2", task.getTid());
        verify(workerManager).releaseWorkerReservation("worker-3", task.getTid());
        verify(workerManager).releaseWorkerReservation("worker-4", task.getTid());
        verify(workerManager).releaseWorkerExclusiveLease("worker-2");
        verify(workerManager).releaseWorkerExclusiveLease("worker-3");
        verify(workerManager).releaseWorkerExclusiveLease("worker-4");
    }

    @Test
    void onTaskAssignReturnsWhenNoWorkerMatches() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);

        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(10);
        when(matchingStrategy.matchWorkers(same(task), eq(2))).thenReturn(List.of());

        assertFalse(listener.onTaskAssign(task));

        assertEquals(TaskStatus.READY, task.getStatus());
        assertEquals(0, task.getPeakAssignedWorkerCount());
        verify(matchingStrategy).matchWorkers(same(task), eq(2));
        verify(assignmentRuntime).countDispatchReadyWork(task.getTid());
        verifyNoInteractions(dispatchBinder);
    }

    @Test
    void onTaskAssignSkipsBeforeMatchingWhenWorkerBudgetIsExhausted() {
        Task task = createTask(100, 1, 1, TaskStatus.RUNNING);

        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(100);
        when(workerManager.getActiveWorkerCountForTask(task.getTid()))
                .thenReturn(com.xa.mass.engine.assignment.DefaultWorkerBudgetPolicy.DEFAULT_BULK_MAX_WORKERS);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertFalse(listener.onTaskAssign(task));
            capture.assertHasEvent("ASSIGNMENT_SUMMARY", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "SKIPPED".equals(mdc.get("result"))
                            && "0".equals(mdc.get("desiredDispatchWorkerCount"))
                            && "0".equals(mdc.get("requestedMatchCount"))
                            && "20".equals(mdc.get("workerBudget"))
                            && "20".equals(mdc.get("currentTaskWorkerCount"))
                            && "true".equals(mdc.get("budgetLimited"))
                            && mdc.get("reason").contains("worker budget exhausted"));
        }

        verifyNoInteractions(matchingStrategy);
        verifyNoInteractions(dispatchBinder);
        verify(assignmentRuntime, never()).updateTask(same(task));
    }

    @Test
    void onTaskAssignUnlocksDispatchCandidatesWhenNoBindingsAreProduced() {
        Task task = createTask(1, 1, 1, TaskStatus.RUNNING);
        Worker worker = createWorker("worker-1");
        WorkerSchedulingCandidate matchedWorker = matched(worker);

        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(1);
        when(matchingStrategy.matchWorkers(same(task), eq(1))).thenReturn(List.of(matchedWorker));
        when(dispatchBinder.bindDispatches(same(task), eq(List.of(matchedWorker)))).thenReturn(List.of());

        assertFalse(listener.onTaskAssign(task));

        verify(workerManager).releaseWorkerExclusiveLease("worker-1");
        verify(workerManager, never()).recordWarmCandidate(
                argThat((WorkerTaskSelector selector) -> "task-1".equals(selector.taskId())),
                argThat((WorkerCandidateRow row) -> "worker-1".equals(row.workerId())));
        verify(assignmentRuntime, never()).updateTask(same(task));
    }

    @Test
    void onTaskAssignSkipsDispatchIfTaskLeavesReadyDuringMatching() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);
        Worker worker = createWorker("worker-1");
        WorkerSchedulingCandidate matchedWorker = matched(worker);

        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(10);
        when(matchingStrategy.matchWorkers(same(task), eq(2))).thenAnswer(invocation -> {
            task.setStatus(TaskStatus.PAUSED);
            return List.of(matchedWorker);
        });

        assertFalse(listener.onTaskAssign(task));

        assertEquals(TaskStatus.PAUSED, task.getStatus());
        assertEquals(0, task.getPeakAssignedWorkerCount());
        verify(matchingStrategy).matchWorkers(same(task), eq(2));
        verify(workerManager).releaseWorkerReservation("worker-1", task.getTid());
        verify(workerManager).releaseWorkerExclusiveLease("worker-1");
        verify(assignmentRuntime, never()).updateTask(task);
        verifyNoInteractions(dispatchBinder);
    }

    @Test
    void onTaskAssignEmitsDispatchSkippedTraceWhenTaskLeavesReadyDuringMatching() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);
        Worker worker = createWorker("worker-1");
        WorkerSchedulingCandidate matchedWorker = matched(worker);

        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(10);
        when(matchingStrategy.matchWorkers(same(task), eq(2))).thenAnswer(invocation -> {
            task.setStatus(TaskStatus.PAUSED);
            return List.of(matchedWorker);
        });

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertFalse(listener.onTaskAssign(task));
            capture.assertHasEvent("DISPATCH_SKIPPED", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "ON_TASK_ASSIGN".equals(mdc.get("trigger"))
                            && "SKIPPED".equals(mdc.get("result"))
                            && mdc.get("reason").contains("status changed during matching"));
        }
    }

    @Test
    void onTaskAssignKeepsTaskReadyUntilMinimumWorkerCountIsMet() {
        Task task = createTask(1, 1, 2, TaskStatus.READY);
        Worker worker = createWorker("worker-1");
        WorkerSchedulingCandidate matchedWorker = matched(worker);

        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(1);
        when(matchingStrategy.matchWorkers(same(task), eq(2))).thenReturn(List.of(matchedWorker));

        assertFalse(listener.onTaskAssign(task));

        assertEquals(TaskStatus.READY, task.getStatus());
        assertEquals(0, task.getPeakAssignedWorkerCount());
        verify(matchingStrategy).matchWorkers(same(task), eq(2));
        verify(workerManager).releaseWorkerExclusiveLease("worker-1");
        verify(assignmentRuntime, never()).updateTask(task);
        verifyNoInteractions(dispatchBinder);
    }

    @Test
    void onTaskAssignEmitsDispatchSkippedTraceWhenBelowMinimumWorkerCount() {
        Task task = createTask(1, 1, 2, TaskStatus.READY);
        Worker worker = createWorker("worker-1");
        WorkerSchedulingCandidate matchedWorker = matched(worker);

        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(1);
        when(matchingStrategy.matchWorkers(same(task), eq(2))).thenReturn(List.of(matchedWorker));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertFalse(listener.onTaskAssign(task));
            capture.assertHasEvent("DISPATCH_SKIPPED", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "2".equals(mdc.get("requiredMinWorkerCount"))
                            && mdc.get("reason").contains("below minimum start gate"));
        }
    }

    @Test
    void onTaskAssignDelegatesWorkerSelectionToInjectedStrategy() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);
        Worker worker = createWorker("worker-1");
        WorkerSchedulingCandidate matchedWorker = matched(worker);

        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(10);
        when(matchingStrategy.matchWorkers(same(task), eq(2))).thenReturn(List.of(matchedWorker));
        when(dispatchBinder.bindDispatches(same(task), eq(List.of(matchedWorker)))).thenReturn(List.of(binding("m1", "worker-1")));

        assertTrue(listener.onTaskAssign(task));
        verify(matchingStrategy).matchWorkers(same(task), eq(2));
    }

    @Test
    void onTaskAssignUsesInjectedAllocationPolicyForMatchRequestAndDispatchCandidates() {
        Task task = createTask(3, 1, 1, TaskStatus.READY);
        WorkerSchedulingCandidate first = matched(createWorker("worker-1"));
        WorkerSchedulingCandidate second = matched(createWorker("worker-2"));
        WorkerSchedulingCandidate third = matched(createWorker("worker-3"));
        AssignmentAllocationPolicy allocationPolicy = new FixedAllocationPolicy(3, 1);
        listener = new TaskWorkerAssignListener(
                matchingStrategy,
                workerManager,
                workerManager,
                dispatchBinder,
                assignmentRuntime,
                assignmentEventSink,
                TraceEventLogger.noop(),
                allocationPolicy
        );

        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(3);
        when(matchingStrategy.matchWorkers(same(task), eq(3))).thenReturn(List.of(first, second, third));
        when(dispatchBinder.bindDispatches(same(task), eq(List.of(first)))).thenReturn(List.of(binding("m1", "worker-1")));

        assertTrue(listener.onTaskAssign(task));

        verify(matchingStrategy).matchWorkers(same(task), eq(3));
        verify(dispatchBinder).bindDispatches(same(task), eq(List.of(first)));
        verify(workerManager).releaseWorkerExclusiveLease("worker-2");
        verify(workerManager).releaseWorkerExclusiveLease("worker-3");
    }

    @Test
    void injectedResourcePolicyOwnsSurplusWorkerUnlockDecision() {
        Task task = createTask(3, 1, 1, TaskStatus.READY);
        WorkerSchedulingCandidate first = matched(createWorker("worker-1"));
        WorkerSchedulingCandidate second = matched(createWorker("worker-2"));
        AssignmentAllocationPolicy allocationPolicy = new FixedAllocationPolicy(2, 1);
        listener = new TaskWorkerAssignListener(
                matchingStrategy,
                workerManager,
                workerManager,
                dispatchBinder,
                assignmentRuntime,
                assignmentEventSink,
                TraceEventLogger.noop(),
                allocationPolicy,
                new NonExclusiveResourcePolicy()
        );

        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(3);
        when(matchingStrategy.matchWorkers(same(task), eq(2))).thenReturn(List.of(first, second));
        when(dispatchBinder.bindDispatches(same(task), eq(List.of(first)))).thenReturn(List.of(binding("m1", "worker-1")));

        assertTrue(listener.onTaskAssign(task));

        verify(workerManager).releaseWorkerReservation("worker-2", task.getTid());
        verify(workerManager, never()).releaseWorkerExclusiveLease("worker-2");
    }

    @Test
    void runningTaskCanBeReplenishedWithoutLeavingRunning() {
        Task task = createTask(5, 2, 1, TaskStatus.RUNNING);
        Worker worker = createWorker("worker-1");
        WorkerSchedulingCandidate matchedWorker = matched(worker);

        when(assignmentRuntime.countDispatchReadyWork(task.getTid())).thenReturn(2);
        when(matchingStrategy.matchWorkers(same(task), eq(1))).thenReturn(List.of(matchedWorker));
        when(dispatchBinder.bindDispatches(same(task), eq(List.of(matchedWorker)))).thenReturn(List.of(binding("m1", "worker-1")));

        assertTrue(listener.onTaskAssign(task));

        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals(1, task.getPeakAssignedWorkerCount());
        verify(assignmentRuntime).updateTask(same(task));
        verify(dispatchBinder).bindDispatches(same(task), eq(List.of(matchedWorker)));
    }

    private Task createTask(int targetNumber, int batchSize, int minWorkerCount, TaskStatus status) {
        Task task = new Task();
        task.setTid("task-1");
        task.setSharedConfig(java.util.Map.of("routingCode", "us"));
        task.setTaskTargetNumber(targetNumber);
        task.getExecutionSpec().setBatchSize(batchSize);
        task.setMinRequiredWorkerCount(minWorkerCount);
        task.setStatus(status);
        return task;
    }

    private Worker createWorker(String workerId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId("pool-a");
        worker.setSupportedProjects(List.of("demoApp"));
        return worker;
    }

    private TaskDispatchBinding binding(String messageId, String workerId) {
        return new TaskDispatchBinding(
                "task-1",
                messageId,
                "demo-event",
                java.util.Map.of("target", "target"),
                null,
                0,
                "attempt-" + messageId,
                1,
                "lease-" + messageId,
                workerId,
                "batch-" + messageId
        );
    }

    private WorkerSchedulingCandidate matched(Worker worker) {
        return new WorkerSchedulingCandidate(
                TestWorkerCandidateRows.from(worker),
                WorkerSchedulingView.from(TestWorkerCandidateRows.from(worker), WorkerReachabilityState.ONLINE,
                        true, false)
        );
    }

    private static final class FixedAllocationPolicy implements AssignmentAllocationPolicy {
        private final int requestedMatchCount;
        private final int dispatchCandidateCount;

        private FixedAllocationPolicy(int requestedMatchCount, int dispatchCandidateCount) {
            this.requestedMatchCount = requestedMatchCount;
            this.dispatchCandidateCount = dispatchCandidateCount;
        }

        @Override
        public AssignmentAllocationPlan plan(AssignmentAllocationRequest request) {
            return new AssignmentAllocationPlan(
                    request.task(),
                    request.initialStatus(),
                    request.readyWorkCount(),
                    request.readyWorkCount(),
                    request.readyWorkCount(),
                    1,
                    requestedMatchCount,
                    dispatchCandidateCount,
                    null,
                    request.currentTaskWorkerCount(),
                    false
            );
        }

        @Override
        public AssignmentAllocationDecision decide(AssignmentAllocationPlan plan,
                                                   TaskStatus currentStatus,
                                                   List<WorkerSchedulingCandidate> matchedWorkers) {
            return new AssignmentAllocationDecision(
                    AssignmentAllocationOutcome.DISPATCH,
                    matchedWorkers.subList(0, dispatchCandidateCount),
                    "fixed allocation dispatch"
            );
        }
    }

    private static final class NonExclusiveResourcePolicy implements WorkerDispatchResourcePolicy {
        @Override
        public WorkerDispatchResourceUsage usageForTask(Task task) {
            return new WorkerDispatchResourceUsage(false);
        }

        @Override
        public WorkerDispatchResourceUsage usageForCandidate(Task task, WorkerSchedulingCandidate candidate) {
            return new WorkerDispatchResourceUsage(false);
        }

        @Override
        public WorkerDispatchResourceUsage usageForAttempt(Task task) {
            return new WorkerDispatchResourceUsage(false);
        }
    }
}
