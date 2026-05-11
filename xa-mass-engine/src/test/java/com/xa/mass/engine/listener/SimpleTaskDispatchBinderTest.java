package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.*;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.ProjectionAwareTaskManager;
import com.xa.mass.engine.TaskQueryService;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.MatchedWorkerContext;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.engine.util.TraceEventLogCapture;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SimpleTaskDispatchBinderTest {

    private WorkerManager workerManager;
    private AssignmentRecordService recordService;
    private InMemoryTaskStorage taskStorage;
    private ProjectionAwareTaskManager taskManager;
    private TaskCommandService taskCommands;
    private TaskQueryService taskQueries;
    private SimpleTaskDispatchBinder listener;

    @BeforeEach
    void setUp() {
        workerManager = mock(WorkerManager.class);
        recordService = mock(AssignmentRecordService.class);
        taskStorage = new InMemoryTaskStorage();
        taskManager = new ProjectionAwareTaskManager(new NoopTaskScheduler(), taskStorage, taskStorage, new InMemoryTaskWorkRuntime());
        taskCommands = new TaskCommandService(taskManager);
        taskQueries = new TaskQueryService(taskManager);
        listener = newAssignmentListener(taskManager);
    }

    @Test
    void usesPersistedTaskMessagesInsteadOfGeneratingNewOnes() {
        Task task = createTask(3);
        task.getExecutionSpec().setBatchSize(10);
        List<String> storedMsgIds = storedMessages(task.getTid()).stream()
                .map(TaskDetailStore.TaskMessageProjection::messageId)
                .collect(Collectors.toList());
        AtomicReference<List<TaskDispatchBinding>> dispatched = new AtomicReference<>();
        listener = new SimpleTaskDispatchBinder(
                taskManager,
                workerManager,
                recordService,
                (t, bindings) -> dispatched.set(bindings)
        );

        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        listener.bindDispatches(task, List.of(matched("d1", "tk1"), matched("d2", "tk2")));

        List<TaskDispatchBinding> pushed = dispatched.get();
        assertNotNull(pushed);
        assertEquals(storedMsgIds, pushed.stream().map(TaskDispatchBinding::messageId).collect(Collectors.toList()));
        assertEquals(List.of("target-0", "target-1", "target-2"),
                pushed.stream().map(binding -> binding.payload().get("target")).collect(Collectors.toList()));
        assertTrue(pushed.stream().allMatch(binding -> binding.workerId() != null));
    }

    @Test
    void assignmentExposesAssignedViewThroughQueryWithoutRestampingStoredMessages() {
        Task task = createTask(4);
        task.getExecutionSpec().setBatchSize(10);

        WorkerContext wc1 = workerContext("tk1", "d1");
        WorkerContext wc2 = workerContext("tk2", "d2");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        listener.bindDispatches(task, List.of(matched(worker("d1"), wc1), matched(worker("d2"), wc2)));

        List<TaskDetailStore.TaskMessageProjection> stored = storedMessages(task.getTid());
        List<TaskDetailStore.TaskMessageProjection> projected = projectedMessages(task.getTid());
        assertEquals(4, projected.size());
        assertTrue(stored.stream().allMatch(msg -> msg.status() == TaskMessageProjectionStatus.INIT));
        assertTrue(stored.stream().allMatch(msg -> msg.latestAttemptWorkerId() == null));
        assertEquals(List.of(
                        TaskMessageProjectionStatus.ASSIGNED,
                        TaskMessageProjectionStatus.ASSIGNED,
                        TaskMessageProjectionStatus.ASSIGNED,
                        TaskMessageProjectionStatus.ASSIGNED),
                projected.stream().map(TaskDetailStore.TaskMessageProjection::status).collect(Collectors.toList()));
        assertEquals(List.of("d1", "d2", "d1", "d2"),
                projected.stream().map(TaskDetailStore.TaskMessageProjection::latestAttemptWorkerId).collect(Collectors.toList()));
        List<String> batchIds = projected.stream().map(TaskDetailStore.TaskMessageProjection::latestAttemptBatchId).collect(Collectors.toList());
        assertTrue(batchIds.stream().allMatch(id -> id != null && !id.isBlank()));
        assertEquals(batchIds.get(0), batchIds.get(2));
        assertEquals(batchIds.get(1), batchIds.get(3));
        assertNotEquals(batchIds.get(0), batchIds.get(1));
        assertTrue(projected.stream().allMatch(msg -> msg.assignedTime() != null));
        assertEquals(WorkerContextStatus.OCCUPIED, wc1.getStatus());
        assertEquals(task.getTid(), wc1.getLastBindTaskId());
        assertEquals(WorkerContextStatus.OCCUPIED, wc2.getStatus());
        assertEquals(task.getTid(), wc2.getLastBindTaskId());

        List<TaskDetailStore.TaskMessageAttemptProjection> attempts = stored.stream()
                .map(msg -> latestActiveAttemptProjection(task.getTid(), msg.messageId()))
                .collect(Collectors.toList());
        assertEquals(4, attempts.size());
        assertTrue(stored.stream().allMatch(msg -> taskStorage.getTaskMessageAttemptProjections(task.getTid(), msg.messageId()).isEmpty()));
        assertTrue(attempts.stream().allMatch(attempt -> attempt.attemptNo() == 1));
        assertTrue(attempts.stream().allMatch(attempt -> attempt.status() == TaskMessageAttemptProjectionStatus.DISPATCHED));
        assertTrue(attempts.stream().allMatch(attempt -> attempt.workerId() != null));
        assertTrue(attempts.stream().allMatch(attempt -> attempt.batchId() != null && !attempt.batchId().isBlank()));
        assertTrue(stored.stream()
                .map(msg -> activeLease(task.getTid(), msg.messageId()))
                .allMatch(lease -> lease != null && lease.leasedAt() != null && lease.leaseExpireAt() != null));

        verify(recordService, times(4)).recordMessageAssignment(
                any(), any(), any(), anyString(), anyString(), any(), anyString(), anyBoolean()
        );
        verify(workerManager, times(4)).isLocked(anyString());
        verify(workerManager, times(2)).updateWorkerContextById(anyString(), any(WorkerContext.class));
    }

    @Test
    void assignmentUsesConfiguredTaskMessageLeaseWindow() {
        taskManager.setWorkLeaseSeconds(2L);
        Task task = createTask(1);
        task.getExecutionSpec().setBatchSize(1);
        WorkerContext wc = workerContext("tk1", "d1");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        LocalDateTime beforeAssign = LocalDateTime.now();
        listener.bindDispatches(task, List.of(matched(worker("d1"), wc)));
        LocalDateTime afterAssign = LocalDateTime.now();

        TaskDetailStore.TaskMessageProjection message = storedMessages(task.getTid()).get(0);
        TaskDetailStore.TaskMessageAttemptProjection attempt = latestActiveAttemptProjection(task.getTid(), message.messageId());
        ActiveLeaseRecord activeLease = activeLease(task.getTid(), message.messageId());

        assertNotNull(attempt);
        assertNotNull(activeLease);
        assertNotNull(activeLease.leaseExpireAt());
        LocalDateTime leaseExpireTime = LocalDateTime.ofInstant(activeLease.leaseExpireAt(), java.time.ZoneId.systemDefault());
        long lowerBound = Duration.between(beforeAssign, leaseExpireTime).getSeconds();
        long upperBound = Duration.between(afterAssign, leaseExpireTime).getSeconds();
        assertTrue(lowerBound >= 1, "lease should be at least about 2 seconds after assignment start");
        assertTrue(upperBound <= 2, "lease should stay close to configured 2-second window");
    }

    @Test
    void interactiveWorkloadUsesSmallPerWorkerClaimWindow() {
        Task task = createTask(5);
        task.getExecutionSpec().setBatchSize(4);
        task.getExecutionSpec().setWorkloadClass(TaskWorkloadClass.INTERACTIVE);

        WorkerContext wc1 = workerContext("tk1", "d1");
        WorkerContext wc2 = workerContext("tk2", "d2");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        List<TaskDispatchBinding> dispatched = listener.bindDispatches(task, List.of(matched(worker("d1"), wc1), matched(worker("d2"), wc2)));

        assertEquals(2, dispatched.size());
        List<TaskDetailStore.TaskMessageProjection> stored = projectedMessages(task.getTid());
        assertEquals(List.of(
                        TaskMessageProjectionStatus.ASSIGNED,
                        TaskMessageProjectionStatus.ASSIGNED,
                        TaskMessageProjectionStatus.INIT,
                        TaskMessageProjectionStatus.INIT,
                        TaskMessageProjectionStatus.INIT),
                stored.stream().map(TaskDetailStore.TaskMessageProjection::status).collect(Collectors.toList()));
        assertEquals(java.util.Arrays.asList("d1", "d2", null, null, null),
                stored.stream().map(TaskDetailStore.TaskMessageProjection::latestAttemptWorkerId).collect(Collectors.toList()));
    }

    @Test
    void interactiveWorkloadCapsLeaseWindowToShortProfile() {
        taskManager.setWorkLeaseSeconds(120L);
        Task task = createTask(1);
        task.getExecutionSpec().setBatchSize(3);
        task.getExecutionSpec().setWorkloadClass(TaskWorkloadClass.INTERACTIVE);
        WorkerContext wc = workerContext("tk1", "d1");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        LocalDateTime beforeAssign = LocalDateTime.now();
        listener.bindDispatches(task, List.of(matched(worker("d1"), wc)));
        LocalDateTime afterAssign = LocalDateTime.now();

        TaskDetailStore.TaskMessageProjection message = storedMessages(task.getTid()).get(0);
        TaskDetailStore.TaskMessageAttemptProjection attempt = latestActiveAttemptProjection(task.getTid(), message.messageId());
        ActiveLeaseRecord activeLease = activeLease(task.getTid(), message.messageId());

        assertNotNull(attempt);
        assertNotNull(activeLease);
        assertNotNull(activeLease.leaseExpireAt());
        LocalDateTime leaseExpireTime = LocalDateTime.ofInstant(activeLease.leaseExpireAt(), java.time.ZoneId.systemDefault());
        long lowerBound = Duration.between(beforeAssign, leaseExpireTime).getSeconds();
        long upperBound = Duration.between(afterAssign, leaseExpireTime).getSeconds();
        assertTrue(lowerBound >= 29, "interactive short lease should stay close to 30 seconds");
        assertTrue(upperBound <= 30, "interactive short lease should be capped by the short lease profile");
    }

    @Test
    void assignmentDoesNotReadLatestAttemptToAllocateDispatchAttemptNo() {
        TrackingLatestAttemptStorage trackingStorage = new TrackingLatestAttemptStorage();
        taskManager = new ProjectionAwareTaskManager(new NoopTaskScheduler(), trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());
        taskCommands = new TaskCommandService(taskManager);
        taskQueries = new TaskQueryService(taskManager);
        listener = newAssignmentListener(taskManager);

        Task task = createTask(3);
        task.getExecutionSpec().setBatchSize(2);
        WorkerContext wc1 = workerContext("tk1", "d1");
        WorkerContext wc2 = workerContext("tk2", "d2");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        List<TaskDispatchBinding> dispatched = listener.bindDispatches(task, List.of(matched(worker("d1"), wc1), matched(worker("d2"), wc2)));

        assertEquals(3, dispatched.size());
        assertEquals(0, trackingStorage.latestAttemptReadCount.get(),
                "dispatch should allocate attempt numbers from runtime retry truth without reading latest attempt rows");
    }

    @Test
    void dispatchPayloadUsesRuntimeClaimInsteadOfProjectionInput() {
        ProjectionPayloadScrubbingStorage scrubbingStorage = new ProjectionPayloadScrubbingStorage();
        taskManager = new ProjectionAwareTaskManager(new NoopTaskScheduler(), scrubbingStorage, scrubbingStorage, new InMemoryTaskWorkRuntime());
        taskCommands = new TaskCommandService(taskManager);
        taskQueries = new TaskQueryService(taskManager);
        listener = newAssignmentListener(taskManager);

        Task task = createTask(1);
        task.getExecutionSpec().setBatchSize(1);
        WorkerContext wc = workerContext("tk1", "d1");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        AtomicReference<List<TaskDispatchBinding>> dispatched = new AtomicReference<>();
        listener = new SimpleTaskDispatchBinder(
                taskManager,
                workerManager,
                recordService,
                (t, bindings) -> dispatched.set(bindings)
        );

        listener.bindDispatches(task, List.of(matched(worker("d1"), wc)));

        List<TaskDispatchBinding> bindings = dispatched.get();
        assertNotNull(bindings);
        assertEquals(1, bindings.size());
        assertEquals("target-0", bindings.get(0).payload().get("target"));
    }

    @Test
    void dispatchDoesNotReadTaskMessageProjectionOnHotPath() {
        TrackingTaskMessageReadStorage trackingStorage = new TrackingTaskMessageReadStorage();
        taskManager = new ProjectionAwareTaskManager(new NoopTaskScheduler(), trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());
        taskCommands = new TaskCommandService(taskManager);
        taskQueries = new TaskQueryService(taskManager);
        listener = newAssignmentListener(taskManager);

        Task task = createTask(3);
        task.getExecutionSpec().setBatchSize(3);
        WorkerContext wc = workerContext("tk1", "d1");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        List<TaskDispatchBinding> dispatched = listener.bindDispatches(task, List.of(matched(worker("d1"), wc)));

        assertEquals(3, dispatched.size());
        assertEquals(0, trackingStorage.taskMessageReadCount.get(),
                "dispatch should synchronize compatibility status without reading message projection first");
    }

    @Test
    void dispatchContinuesWhenCompatibilityAttemptProjectionWriteFails() {
        FailingAddAttemptStorage failingStorage = new FailingAddAttemptStorage();
        taskStorage = failingStorage;
        taskManager = new ProjectionAwareTaskManager(new NoopTaskScheduler(), failingStorage, failingStorage, new InMemoryTaskWorkRuntime());
        taskCommands = new TaskCommandService(taskManager);
        taskQueries = new TaskQueryService(taskManager);
        listener = newAssignmentListener(taskManager);

        Task task = createTask(1);
        task.getExecutionSpec().setBatchSize(1);
        WorkerContext wc = workerContext("tk1", "d1");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        List<TaskDispatchBinding> dispatched = listener.bindDispatches(task, List.of(matched(worker("d1"), wc)));

        assertEquals(1, dispatched.size());
        TaskDetailStore.TaskMessageProjection stored = storedMessages(task.getTid()).get(0);
        TaskDetailStore.TaskMessageProjection visible = taskMessageProjection(task.getTid(), stored.messageId());
        assertEquals(TaskMessageProjectionStatus.INIT, stored.status());
        assertEquals(TaskMessageProjectionStatus.ASSIGNED, visible.status());
        assertEquals("d1", visible.latestAttemptWorkerId());
        assertTrue(failingStorage.getTaskMessageAttemptProjections(task.getTid(), stored.messageId()).isEmpty());
    }

    @Test
    void dispatchSubmitFailureCompensatesRuntimeProjectionAndWorkerContextForRetry() {
        Task task = createTask(2);
        task.getExecutionSpec().setBatchSize(2);
        WorkerContext wc = workerContext("tk1", "d1");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        listener = new SimpleTaskDispatchBinder(
                taskManager,
                workerManager,
                recordService,
                (t, bindings) -> {
                    throw new IllegalStateException("handoff queue unavailable");
                }
        );

        List<TaskDispatchBinding> dispatched = listener.bindDispatches(task, List.of(matched(worker("d1"), wc)));

        assertTrue(dispatched.isEmpty());
        List<TaskDetailStore.TaskMessageProjection> stored = storedMessages(task.getTid());
        assertEquals(List.of(TaskMessageProjectionStatus.INIT, TaskMessageProjectionStatus.INIT),
                stored.stream().map(TaskDetailStore.TaskMessageProjection::status).collect(Collectors.toList()));
        assertTrue(stored.stream().allMatch(msg -> msg.retryCount() == 1));
        assertTrue(stored.stream().allMatch(msg -> msg.latestAttemptId() == null));
        assertTrue(stored.stream().allMatch(msg -> msg.latestAttemptWorkerId() == null));
        assertTrue(stored.stream().allMatch(msg -> msg.latestAttemptWorkerContextId() == null));
        assertTrue(stored.stream().allMatch(msg -> msg.latestAttemptBatchId() == null));

        List<TaskDetailStore.TaskMessageAttemptProjection> attempts = stored.stream()
                .map(msg -> taskStorage.getLatestTaskMessageAttemptProjection(task.getTid(), msg.messageId()).orElseThrow())
                .collect(Collectors.toList());
        assertTrue(attempts.stream().allMatch(attempt -> attempt.status() == TaskMessageAttemptProjectionStatus.REVOKED));
        assertTrue(attempts.stream().allMatch(attempt -> attempt.finalReason()
                == TaskMessageAttemptProjectionFinalReason.REVOKED_FOR_RETRY));
        assertTrue(attempts.stream().allMatch(attempt -> "DISPATCH_SUBMIT_FAILED".equals(attempt.errorCode())));

        assertEquals(WorkerContextStatus.IDLE, wc.getStatus());
        assertNull(wc.getLastBindTaskId());
        verify(workerManager, times(2)).updateWorkerContextById(eq("tk1"), same(wc));
        assertEquals(2, taskManager.countDispatchReadyWork(task.getTid()));

        AtomicReference<List<TaskDispatchBinding>> recoveredDispatch = new AtomicReference<>();
        listener = new SimpleTaskDispatchBinder(
                taskManager,
                workerManager,
                recordService,
                (t, bindings) -> recoveredDispatch.set(bindings)
        );
        listener.bindDispatches(task, List.of(matched(worker("d1"), wc)));

        List<TaskDispatchBinding> retryDispatch = recoveredDispatch.get();
        assertNotNull(retryDispatch);
        assertEquals(2, retryDispatch.size());
    }

    @Test
    void assignmentEmitsAttemptAndWorkerContextTraceEvents() {
        Task task = createTask(1);
        task.getExecutionSpec().setBatchSize(1);
        WorkerContext wc = workerContext("tk1", "d1");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            listener.bindDispatches(task, List.of(matched(worker("d1"), wc)));

            capture.assertHasEvent("TASK_MSG_ATTEMPT_STATUS_TRANSITION", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && "CREATED".equals(mdc.get("fromStatus"))
                            && "LEASED".equals(mdc.get("toStatus"))
                            && "d1".equals(mdc.get("workerId"))
                            && "tk1".equals(mdc.get("workerContextId")));
            capture.assertHasEvent("TASK_MSG_ATTEMPT_STATUS_TRANSITION", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && "LEASED".equals(mdc.get("fromStatus"))
                            && "DISPATCHED".equals(mdc.get("toStatus"))
                            && "d1".equals(mdc.get("workerId"))
                            && "tk1".equals(mdc.get("workerContextId")));
            capture.assertHasEvent("WORKER_CONTEXT_STATUS_TRANSITION", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && "IDLE".equals(mdc.get("fromStatus"))
                            && "RESERVED".equals(mdc.get("toStatus"))
                            && "tk1".equals(mdc.get("workerContextId")));
            capture.assertHasEvent("WORKER_CONTEXT_STATUS_TRANSITION", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && "RESERVED".equals(mdc.get("fromStatus"))
                            && "OCCUPIED".equals(mdc.get("toStatus"))
                            && "tk1".equals(mdc.get("workerContextId")));
        }
    }

    @Test
    void assignmentEmitsDispatchBindingSummary() {
        Task task = createTask(3);
        task.getExecutionSpec().setBatchSize(2);
        WorkerContext wc1 = workerContext("tk1", "d1");
        WorkerContext wc2 = workerContext("tk2", "d2");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            List<TaskDispatchBinding> dispatched = listener.bindDispatches(task, List.of(matched(worker("d1"), wc1), matched(worker("d2"), wc2)));
            assertEquals(3, dispatched.size());
            capture.assertHasEvent("DISPATCH_BINDING_SUMMARY", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && "3".equals(mdc.get("pendingMessageCount"))
                            && "2".equals(mdc.get("matchedWorkerCount"))
                            && "2".equals(mdc.get("dispatchSlotCount"))
                            && "3".equals(mdc.get("dispatchedMessageCount"))
                            && "2".equals(mdc.get("uniqueWorkerCount"))
                            && "2".equals(mdc.get("uniqueWorkerContextCount"))
                            && "2".equals(mdc.get("perWorkerBatchLimit"))
                            && "0".equals(mdc.get("unassignedMessageCount"))
                            && "SUCCESS".equals(mdc.get("result")));
        }
    }

    @Test
    void assignmentRespectsPerWorkerBatchSizeAndLeavesRemainingMessagesPending() {
        Task task = createTask(5);
        task.getExecutionSpec().setBatchSize(2);

        WorkerContext wc1 = workerContext("tk1", "d1");
        WorkerContext wc2 = workerContext("tk2", "d2");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        List<TaskDispatchBinding> dispatched = listener.bindDispatches(task, List.of(matched(worker("d1"), wc1), matched(worker("d2"), wc2)));

        assertEquals(4, dispatched.size());
        List<TaskDetailStore.TaskMessageProjection> stored = projectedMessages(task.getTid());
        assertEquals(List.of(
                        TaskMessageProjectionStatus.ASSIGNED,
                        TaskMessageProjectionStatus.ASSIGNED,
                        TaskMessageProjectionStatus.ASSIGNED,
                        TaskMessageProjectionStatus.ASSIGNED,
                        TaskMessageProjectionStatus.INIT),
                stored.stream().map(TaskDetailStore.TaskMessageProjection::status).collect(Collectors.toList()));
        assertEquals(java.util.Arrays.asList("d1", "d2", "d1", "d2", null),
                stored.stream().map(TaskDetailStore.TaskMessageProjection::latestAttemptWorkerId).collect(Collectors.toList()));
    }

    @Test
    void singleWorkerDoesNotExceedBatchSizeWithinOneDispatchRound() {
        Task task = createTask(4);
        task.getExecutionSpec().setBatchSize(2);

        WorkerContext wc1 = workerContext("tk1", "d1");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        List<TaskDispatchBinding> dispatched = listener.bindDispatches(task, List.of(matched(worker("d1"), wc1)));

        assertEquals(2, dispatched.size());
        List<TaskDetailStore.TaskMessageProjection> stored = projectedMessages(task.getTid());
        assertEquals(List.of(
                        TaskMessageProjectionStatus.ASSIGNED,
                        TaskMessageProjectionStatus.ASSIGNED,
                        TaskMessageProjectionStatus.INIT,
                        TaskMessageProjectionStatus.INIT),
                stored.stream().map(TaskDetailStore.TaskMessageProjection::status).collect(Collectors.toList()));
        assertEquals(java.util.Arrays.asList("d1", "d1", null, null),
                stored.stream().map(TaskDetailStore.TaskMessageProjection::latestAttemptWorkerId).collect(Collectors.toList()));
    }

    @Test
    void finalDispatchRoundCanUseLessThanBatchSizeWhenFewerMessagesRemain() {
        Task task = createTask(3);
        task.getExecutionSpec().setBatchSize(2);

        WorkerContext wc1 = workerContext("tk1", "d1");
        WorkerContext wc2 = workerContext("tk2", "d2");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        List<TaskDispatchBinding> dispatched = listener.bindDispatches(task, List.of(matched(worker("d1"), wc1), matched(worker("d2"), wc2)));

        assertEquals(3, dispatched.size());
        List<TaskDetailStore.TaskMessageProjection> stored = projectedMessages(task.getTid());
        assertEquals(List.of(
                        TaskMessageProjectionStatus.ASSIGNED,
                        TaskMessageProjectionStatus.ASSIGNED,
                        TaskMessageProjectionStatus.ASSIGNED),
                stored.stream().map(TaskDetailStore.TaskMessageProjection::status).collect(Collectors.toList()));
        assertEquals(java.util.Arrays.asList("d1", "d2", "d1"),
                stored.stream().map(TaskDetailStore.TaskMessageProjection::latestAttemptWorkerId).collect(Collectors.toList()));
    }

    @Test
    void nullWorkerContextIsHandledGracefully() {
        Task task = createTask(2);
        task.getExecutionSpec().setBatchSize(10);
        assertDoesNotThrow(() -> listener.bindDispatches(task, List.of(new MatchedWorkerContext(worker("d1"), null))));

        List<TaskDetailStore.TaskMessageProjection> stored = projectedMessages(task.getTid());
        assertTrue(stored.stream().allMatch(msg -> msg.latestAttemptWorkerContextId() == null));
        assertTrue(stored.stream().allMatch(msg -> msg.latestAttemptBatchId() != null && !msg.latestAttemptBatchId().isBlank()));
        verify(recordService, times(2)).recordMessageAssignment(
                any(), any(), isNull(), anyString(), anyString(), any(), anyString(), anyBoolean()
        );
        verify(workerManager, times(2)).isLocked("d1");
    }

    @Test
    void nonDispatchableWorkerContextSkipsWorkerAndUnlocksIt() {
        Task task = createTask(1);
        WorkerContext blocked = workerContext("tk-blocked", "d1");
        blocked.block();
        assertTrue(listener.bindDispatches(task, List.of(matched(worker("d1"), blocked))).isEmpty());

        List<TaskDetailStore.TaskMessageProjection> stored = storedMessages(task.getTid());
        assertEquals(TaskMessageProjectionStatus.INIT, stored.get(0).status());
        verify(workerManager).unlockWorker("d1");
        verify(recordService, never()).recordMessageAssignment(
                any(), any(), any(), anyString(), anyString(), any(), anyString(), anyBoolean()
        );
    }

    @Test
    void emptyWorkerListSkipsWithoutMutation() {
        Task task = createTask(2);
        List<String> before = storedMessages(task.getTid()).stream()
                .map(TaskDetailStore.TaskMessageProjection::messageId)
                .collect(Collectors.toList());

        assertTrue(listener.bindDispatches(task, List.of()).isEmpty());

        List<TaskDetailStore.TaskMessageProjection> after = storedMessages(task.getTid());
        assertEquals(before, after.stream().map(TaskDetailStore.TaskMessageProjection::messageId).collect(Collectors.toList()));
        assertTrue(after.stream().allMatch(msg -> msg.status() == TaskMessageProjectionStatus.INIT));
        verifyNoInteractions(recordService);
    }

    private Task createTask(int messageCount) {
        TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
        dto.setSourceRef("task");
        dto.setProject("demoApp");
        dto.setSharedConfig(java.util.Map.of("textContent", "hello", "routingCode", "us"));
        dto.setUserId("agent");
        TaskExecutionSpec spec = new TaskExecutionSpec();
        spec.setBatchSize(1);
        spec.setDefaultMaxRetryCount(3);
        dto.setExecutionSpec(spec);
        Task task = taskCommands.createTaskShell(dto);
        taskCommands.appendTaskItems(task.getTid(), IntStream.range(0, messageCount)
                .mapToObj(i -> java.util.Map.<String, Object>of("target", "target-" + i))
                .collect(Collectors.toCollection(ArrayList::new)));
        assertTrue(taskCommands.sealTask(task.getTid()));
        return taskQueries.getTask(task.getTid());
    }

    private List<TaskDetailStore.TaskMessageProjection> storedMessages(String taskId) {
        long count = taskStorage.getTaskMessageStats(taskId).getTotal();
        if (count == 0) {
            return List.of();
        }
        return taskStorage.getTaskMessageProjections(taskId, Math.toIntExact(count));
    }

    private List<TaskDetailStore.TaskMessageProjection> projectedMessages(String taskId) {
        return storedMessages(taskId).stream()
                .map(msg -> taskMessageProjection(taskId, msg.messageId()))
                .collect(Collectors.toList());
    }

    private TaskDetailStore.TaskMessageProjection taskMessageProjection(String taskId, String messageId) {
        return taskManager.getVisibleTaskMessageProjection(taskId, messageId);
    }

    private TaskDetailStore.TaskMessageAttemptProjection latestActiveAttemptProjection(String taskId, String messageId) {
        return taskManager.getLatestActiveAttemptProjectionRecord(taskId, messageId);
    }

    private ActiveLeaseRecord activeLease(String taskId, String messageId) {
        return taskManager.getActiveLeaseRecord(taskId, messageId);
    }

    private Worker worker(String id) {
        Worker w = new Worker();
        w.setWorkerId(id);
        return w;
    }

    private WorkerContext workerContext(String workerContextId, String workerId) {
        WorkerContext wc = new WorkerContext();
        wc.setWorkerContextId(workerContextId);
        wc.setWorkerId(workerId);
        return wc;
    }

    private MatchedWorkerContext matched(String workerId, String workerContextId) {
        return matched(worker(workerId), workerContext(workerContextId, workerId));
    }

    private MatchedWorkerContext matched(Worker worker, WorkerContext workerContext) {
        return new MatchedWorkerContext(worker, workerContext);
    }

    private SimpleTaskDispatchBinder newAssignmentListener(ProjectionAwareTaskManager manager) {
        return new SimpleTaskDispatchBinder(
                manager,
                workerManager,
                recordService
        );
    }

    private static class NoopTaskScheduler implements TaskScheduler {
        @Override
        public SchedulingResult scheduleTask(Task task) {
            return SchedulingResult.success();
        }

        @Override
        public List<SchedulingResult> scheduleTasks(List<Task> tasks) {
            return List.of();
        }

        @Override
        public boolean retryTaskDispatchUnit(String taskId, String messageId) {
            return true;
        }

        @Override
        public boolean cancelTask(String taskId) {
            return true;
        }

        @Override
        public boolean pauseTask(String taskId) {
            return true;
        }

        @Override
        public boolean resumeTask(String taskId) {
            return true;
        }
    }

    private static final class TrackingLatestAttemptStorage extends InMemoryTaskStorage {
        private final AtomicInteger latestAttemptReadCount = new AtomicInteger();

        @Override
        public Optional<TaskDetailStore.TaskMessageAttemptProjection> getLatestTaskMessageAttemptProjection(String taskId,
                                                                                                            String messageId) {
            latestAttemptReadCount.incrementAndGet();
            return super.getLatestTaskMessageAttemptProjection(taskId, messageId);
        }
    }

    private static final class ProjectionPayloadScrubbingStorage extends InMemoryTaskStorage {
        @Override
        public Optional<TaskDetailStore.TaskMessageProjection> getTaskMessageProjection(String taskId, String messageId) {
            return super.getTaskMessageProjection(taskId, messageId)
                    .map(projection -> new TaskDetailStore.TaskMessageProjection(
                            projection.messageId(),
                            projection.taskId(),
                            java.util.Map.of(),
                            projection.payloadRef(),
                            projection.status(),
                            projection.assignedTime(),
                            projection.createTime(),
                            projection.updateTime(),
                            projection.startTime(),
                            projection.completeTime(),
                            projection.retryCount(),
                            projection.maxRetryCount(),
                            projection.errorMessage(),
                            projection.errorCode(),
                            projection.finalReason(),
                            projection.output(),
                            projection.latestAttemptId(),
                            projection.latestAttemptWorkerId(),
                            projection.latestAttemptWorkerContextId(),
                            projection.latestAttemptBatchId()
                    ));
        }
    }

    private static final class TrackingTaskMessageReadStorage extends InMemoryTaskStorage {
        private final AtomicInteger taskMessageReadCount = new AtomicInteger();

        @Override
        public Optional<TaskDetailStore.TaskMessageProjection> getTaskMessageProjection(String taskId, String messageId) {
            taskMessageReadCount.incrementAndGet();
            return super.getTaskMessageProjection(taskId, messageId);
        }
    }

    private static final class FailingAddAttemptStorage extends InMemoryTaskStorage {
        @Override
        public boolean upsertTaskMessageAttemptProjection(String taskId,
                                                          String messageId,
                                                          TaskDetailStore.TaskMessageAttemptProjection projection) {
            throw new IllegalStateException("compatibility attempt projection unavailable");
        }
    }
}




