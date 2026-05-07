package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.*;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.MatchedWorkerContext;
import com.xa.mass.base.model.TaskCreateRequestDto;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.engine.util.TraceEventLogCapture;
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

class SimpleTaskMsgAssignListenerTest {

    private WorkerManager workerManager;
    private AssignmentRecordService recordService;
    private InMemoryTaskStorage taskStorage;
    private TaskManager taskManager;
    private TaskCommandService taskCommands;
    private SimpleTaskMsgAssignListener listener;

    @BeforeEach
    void setUp() {
        workerManager = mock(WorkerManager.class);
        recordService = mock(AssignmentRecordService.class);
        taskStorage = new InMemoryTaskStorage();
        taskManager = new TaskManager(new NoopTaskScheduler(), taskStorage, taskStorage, new InMemoryTaskWorkRuntime());
        taskCommands = new TaskCommandService(taskManager);
        listener = newAssignmentListener(taskManager);
    }

    @Test
    void usesPersistedTaskMessagesInsteadOfGeneratingNewOnes() {
        Task task = createTask(3);
        task.setBatchSize(10);
        List<String> storedMsgIds = storedMessages(task.getTid()).stream()
                .map(TaskMsg::getMessageId)
                .collect(Collectors.toList());
        AtomicReference<List<TaskDispatchBinding>> dispatched = new AtomicReference<>();
        listener = new SimpleTaskMsgAssignListener(
                taskManager,
                workerManager,
                recordService,
                (t, bindings) -> dispatched.set(bindings)
        );

        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        listener.onMsgAssign(task, List.of(matched("d1", "tk1"), matched("d2", "tk2")));

        List<TaskDispatchBinding> pushed = dispatched.get();
        assertNotNull(pushed);
        assertEquals(storedMsgIds, pushed.stream().map(binding -> binding.taskMsg().getMessageId()).collect(Collectors.toList()));
        assertEquals(List.of("target-0", "target-1", "target-2"),
                pushed.stream().map(binding -> binding.taskMsg().getInput().get("target")).collect(Collectors.toList()));
        assertTrue(pushed.stream().allMatch(binding -> binding.attempt().getWorkerId() != null));
    }

    @Test
    void assignmentWritesWorkerBatchAndAssignedStatusBackToStorage() {
        Task task = createTask(4);
        task.setBatchSize(10);

        WorkerContext wc1 = workerContext("tk1", "d1");
        WorkerContext wc2 = workerContext("tk2", "d2");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        listener.onMsgAssign(task, List.of(matched(worker("d1"), wc1), matched(worker("d2"), wc2)));

        List<TaskMsg> stored = storedMessages(task.getTid());
        assertEquals(4, stored.size());
        assertEquals(List.of(TaskMsgStatus.ASSIGNED, TaskMsgStatus.ASSIGNED, TaskMsgStatus.ASSIGNED, TaskMsgStatus.ASSIGNED),
                stored.stream().map(TaskMsg::getStatus).collect(Collectors.toList()));
        assertEquals(List.of("d1", "d2", "d1", "d2"),
                stored.stream().map(TaskMsg::getLatestAttemptWorkerId).collect(Collectors.toList()));
        List<String> batchIds = stored.stream().map(TaskMsg::getLatestAttemptBatchId).collect(Collectors.toList());
        assertTrue(batchIds.stream().allMatch(id -> id != null && !id.isBlank()));
        assertEquals(batchIds.get(0), batchIds.get(2));
        assertEquals(batchIds.get(1), batchIds.get(3));
        assertNotEquals(batchIds.get(0), batchIds.get(1));
        assertTrue(stored.stream().allMatch(msg -> msg.getAssignedTime() != null));
        assertEquals(WorkerContextStatus.OCCUPIED, wc1.getStatus());
        assertEquals(task.getTid(), wc1.getLastBindTaskId());
        assertEquals(WorkerContextStatus.OCCUPIED, wc2.getStatus());
        assertEquals(task.getTid(), wc2.getLastBindTaskId());

        List<TaskMsgAttempt> attempts = stored.stream()
                .map(msg -> taskStorage.getTaskMessageAttempts(task.getTid(), msg.getMessageId()))
                .flatMap(List::stream)
                .collect(Collectors.toList());
        assertEquals(4, attempts.size());
        assertTrue(attempts.stream().allMatch(attempt -> attempt.getAttemptNo() == 1));
        assertTrue(attempts.stream().allMatch(attempt -> attempt.getStatus() == TaskMsgAttemptStatus.DISPATCHED));
        assertTrue(attempts.stream().allMatch(attempt -> attempt.getWorkerId() != null));
        assertTrue(attempts.stream().allMatch(attempt -> attempt.getDispatchTime() != null));
        assertTrue(attempts.stream().allMatch(attempt -> attempt.getBatchId() != null && !attempt.getBatchId().isBlank()));
        assertTrue(stored.stream().allMatch(msg -> {
            TaskMsgAttempt latestAttempt = taskStorage.getLatestTaskMessageAttempt(task.getTid(), msg.getMessageId())
                    .orElse(null);
            return latestAttempt != null && latestAttempt.getAttemptId().equals(msg.latestAttemptId());
        }));

        verify(recordService, times(4)).recordMessageAssignment(
                any(), any(), any(), anyString(), anyString(), any(), anyString(), anyBoolean()
        );
        verify(workerManager, times(4)).isLocked(anyString());
        verify(workerManager, times(2)).updateWorkerContextById(anyString(), any(WorkerContext.class));
    }

    @Test
    void assignmentUsesConfiguredTaskMessageLeaseWindow() {
        taskManager.setTaskMessageLeaseSeconds(2L);
        Task task = createTask(1);
        task.setBatchSize(1);
        WorkerContext wc = workerContext("tk1", "d1");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        LocalDateTime beforeAssign = LocalDateTime.now();
        listener.onMsgAssign(task, List.of(matched(worker("d1"), wc)));
        LocalDateTime afterAssign = LocalDateTime.now();

        TaskMsg message = storedMessages(task.getTid()).get(0);
        TaskMsgAttempt attempt = taskStorage.getLatestTaskMessageAttempt(task.getTid(), message.getMessageId())
                .orElse(null);

        assertNotNull(attempt);
        assertNotNull(attempt.getLeaseExpireTime());
        long lowerBound = Duration.between(beforeAssign, attempt.getLeaseExpireTime()).getSeconds();
        long upperBound = Duration.between(afterAssign, attempt.getLeaseExpireTime()).getSeconds();
        assertTrue(lowerBound >= 1, "lease should be at least about 2 seconds after assignment start");
        assertTrue(upperBound <= 2, "lease should stay close to configured 2-second window");
    }

    @Test
    void interactiveWorkloadUsesSmallPerWorkerClaimWindow() {
        Task task = createTask(5);
        task.setBatchSize(4);
        task.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);

        WorkerContext wc1 = workerContext("tk1", "d1");
        WorkerContext wc2 = workerContext("tk2", "d2");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        List<TaskDispatchBinding> dispatched = listener.onMsgAssign(task, List.of(matched(worker("d1"), wc1), matched(worker("d2"), wc2)));

        assertEquals(2, dispatched.size());
        List<TaskMsg> stored = storedMessages(task.getTid());
        assertEquals(List.of(
                        TaskMsgStatus.ASSIGNED,
                        TaskMsgStatus.ASSIGNED,
                        TaskMsgStatus.INIT,
                        TaskMsgStatus.INIT,
                        TaskMsgStatus.INIT),
                stored.stream().map(TaskMsg::getStatus).collect(Collectors.toList()));
        assertEquals(java.util.Arrays.asList("d1", "d2", null, null, null),
                stored.stream().map(TaskMsg::getLatestAttemptWorkerId).collect(Collectors.toList()));
    }

    @Test
    void interactiveWorkloadCapsLeaseWindowToShortProfile() {
        taskManager.setTaskMessageLeaseSeconds(120L);
        Task task = createTask(1);
        task.setBatchSize(3);
        task.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);
        WorkerContext wc = workerContext("tk1", "d1");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        LocalDateTime beforeAssign = LocalDateTime.now();
        listener.onMsgAssign(task, List.of(matched(worker("d1"), wc)));
        LocalDateTime afterAssign = LocalDateTime.now();

        TaskMsg message = storedMessages(task.getTid()).get(0);
        TaskMsgAttempt attempt = taskStorage.getLatestTaskMessageAttempt(task.getTid(), message.getMessageId())
                .orElse(null);

        assertNotNull(attempt);
        assertNotNull(attempt.getLeaseExpireTime());
        long lowerBound = Duration.between(beforeAssign, attempt.getLeaseExpireTime()).getSeconds();
        long upperBound = Duration.between(afterAssign, attempt.getLeaseExpireTime()).getSeconds();
        assertTrue(lowerBound >= 29, "interactive short lease should stay close to 30 seconds");
        assertTrue(upperBound <= 30, "interactive short lease should be capped by the short lease profile");
    }

    @Test
    void assignmentDoesNotReadLatestAttemptToAllocateDispatchAttemptNo() {
        TrackingLatestAttemptStorage trackingStorage = new TrackingLatestAttemptStorage();
        taskManager = new TaskManager(new NoopTaskScheduler(), trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());
        taskCommands = new TaskCommandService(taskManager);
        listener = newAssignmentListener(taskManager);

        Task task = createTask(3);
        task.setBatchSize(2);
        WorkerContext wc1 = workerContext("tk1", "d1");
        WorkerContext wc2 = workerContext("tk2", "d2");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        List<TaskDispatchBinding> dispatched = listener.onMsgAssign(task, List.of(matched(worker("d1"), wc1), matched(worker("d2"), wc2)));

        assertEquals(3, dispatched.size());
        assertEquals(0, trackingStorage.latestAttemptReadCount.get(),
                "dispatch should allocate attempt numbers from runtime retry truth without reading latest attempt rows");
    }

    @Test
    void dispatchPayloadUsesRuntimeClaimInsteadOfProjectionInput() {
        ProjectionPayloadScrubbingStorage scrubbingStorage = new ProjectionPayloadScrubbingStorage();
        taskManager = new TaskManager(new NoopTaskScheduler(), scrubbingStorage, scrubbingStorage, new InMemoryTaskWorkRuntime());
        taskCommands = new TaskCommandService(taskManager);
        listener = newAssignmentListener(taskManager);

        Task task = createTask(1);
        task.setBatchSize(1);
        WorkerContext wc = workerContext("tk1", "d1");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        AtomicReference<List<TaskDispatchBinding>> dispatched = new AtomicReference<>();
        listener = new SimpleTaskMsgAssignListener(
                taskManager,
                workerManager,
                recordService,
                (t, bindings) -> dispatched.set(bindings)
        );

        listener.onMsgAssign(task, List.of(matched(worker("d1"), wc)));

        List<TaskDispatchBinding> bindings = dispatched.get();
        assertNotNull(bindings);
        assertEquals(1, bindings.size());
        assertEquals("target-0", bindings.get(0).payload().get("target"));
    }

    @Test
    void dispatchDoesNotReadTaskMessageProjectionOnHotPath() {
        TrackingTaskMessageReadStorage trackingStorage = new TrackingTaskMessageReadStorage();
        taskManager = new TaskManager(new NoopTaskScheduler(), trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());
        taskCommands = new TaskCommandService(taskManager);
        listener = newAssignmentListener(taskManager);

        Task task = createTask(3);
        task.setBatchSize(3);
        WorkerContext wc = workerContext("tk1", "d1");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        List<TaskDispatchBinding> dispatched = listener.onMsgAssign(task, List.of(matched(worker("d1"), wc)));

        assertEquals(3, dispatched.size());
        assertEquals(0, trackingStorage.taskMessageReadCount.get(),
                "dispatch should synchronize compatibility status without reading TaskMsg projection first");
    }

    @Test
    void dispatchContinuesWhenCompatibilityAttemptProjectionWriteFails() {
        FailingAddAttemptStorage failingStorage = new FailingAddAttemptStorage();
        taskStorage = failingStorage;
        taskManager = new TaskManager(new NoopTaskScheduler(), failingStorage, failingStorage, new InMemoryTaskWorkRuntime());
        taskCommands = new TaskCommandService(taskManager);
        listener = newAssignmentListener(taskManager);

        Task task = createTask(1);
        task.setBatchSize(1);
        WorkerContext wc = workerContext("tk1", "d1");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        List<TaskDispatchBinding> dispatched = listener.onMsgAssign(task, List.of(matched(worker("d1"), wc)));

        assertEquals(1, dispatched.size());
        TaskMsg updated = storedMessages(task.getTid()).get(0);
        assertEquals(TaskMsgStatus.ASSIGNED, updated.getStatus());
        assertEquals(dispatched.get(0).attemptId(), updated.latestAttemptId());
        assertTrue(failingStorage.getTaskMessageAttempts(task.getTid(), updated.getMessageId()).isEmpty());
    }

    @Test
    void dispatchSubmitFailureCompensatesRuntimeProjectionAndWorkerContextForRetry() {
        Task task = createTask(2);
        task.setBatchSize(2);
        WorkerContext wc = workerContext("tk1", "d1");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        listener = new SimpleTaskMsgAssignListener(
                taskManager,
                workerManager,
                recordService,
                (t, bindings) -> {
                    throw new IllegalStateException("handoff queue unavailable");
                }
        );

        List<TaskDispatchBinding> dispatched = listener.onMsgAssign(task, List.of(matched(worker("d1"), wc)));

        assertTrue(dispatched.isEmpty());
        List<TaskMsg> stored = storedMessages(task.getTid());
        assertEquals(List.of(TaskMsgStatus.INIT, TaskMsgStatus.INIT),
                stored.stream().map(TaskMsg::getStatus).collect(Collectors.toList()));
        assertTrue(stored.stream().allMatch(msg -> msg.getRetryCount() == 1));
        assertTrue(stored.stream().allMatch(msg -> msg.latestAttemptId() == null));
        assertTrue(stored.stream().allMatch(msg -> msg.getLatestAttemptWorkerId() == null));
        assertTrue(stored.stream().allMatch(msg -> msg.getLatestAttemptWorkerContextId() == null));
        assertTrue(stored.stream().allMatch(msg -> msg.getLatestAttemptBatchId() == null));

        List<TaskMsgAttempt> attempts = stored.stream()
                .map(msg -> taskStorage.getLatestTaskMessageAttempt(task.getTid(), msg.getMessageId()).orElseThrow())
                .collect(Collectors.toList());
        assertTrue(attempts.stream().allMatch(attempt -> attempt.getStatus() == TaskMsgAttemptStatus.REVOKED));
        assertTrue(attempts.stream().allMatch(attempt -> attempt.getFinalReason()
                == com.xa.mass.base.enums.taskmsg.TaskMsgAttemptFinalReason.REVOKED_FOR_RETRY));
        assertTrue(attempts.stream().allMatch(attempt -> "DISPATCH_SUBMIT_FAILED".equals(attempt.getErrorCode())));

        assertEquals(WorkerContextStatus.IDLE, wc.getStatus());
        assertNull(wc.getLastBindTaskId());
        verify(workerManager, times(2)).updateWorkerContextById(eq("tk1"), same(wc));
        assertEquals(2, taskManager.countPendingDispatchableMessages(task.getTid()));

        AtomicReference<List<TaskDispatchBinding>> recoveredDispatch = new AtomicReference<>();
        listener = new SimpleTaskMsgAssignListener(
                taskManager,
                workerManager,
                recordService,
                (t, bindings) -> recoveredDispatch.set(bindings)
        );
        listener.onMsgAssign(task, List.of(matched(worker("d1"), wc)));

        List<TaskDispatchBinding> retryDispatch = recoveredDispatch.get();
        assertNotNull(retryDispatch);
        assertEquals(2, retryDispatch.size());
    }

    @Test
    void assignmentEmitsTaskMsgAndWorkerContextTraceEvents() {
        Task task = createTask(1);
        task.setBatchSize(1);
        WorkerContext wc = workerContext("tk1", "d1");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            listener.onMsgAssign(task, List.of(matched(worker("d1"), wc)));

            capture.assertHasEvent("TASK_MSG_STATUS_TRANSITION", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && "INIT".equals(mdc.get("fromStatus"))
                            && "ASSIGNED".equals(mdc.get("toStatus"))
                            && "d1".equals(mdc.get("workerId"))
                            && "tk1".equals(mdc.get("workerContextId")));
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
        task.setBatchSize(2);
        WorkerContext wc1 = workerContext("tk1", "d1");
        WorkerContext wc2 = workerContext("tk2", "d2");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            List<TaskDispatchBinding> dispatched = listener.onMsgAssign(task, List.of(matched(worker("d1"), wc1), matched(worker("d2"), wc2)));
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
        task.setBatchSize(2);

        WorkerContext wc1 = workerContext("tk1", "d1");
        WorkerContext wc2 = workerContext("tk2", "d2");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        List<TaskDispatchBinding> dispatched = listener.onMsgAssign(task, List.of(matched(worker("d1"), wc1), matched(worker("d2"), wc2)));

        assertEquals(4, dispatched.size());
        List<TaskMsg> stored = storedMessages(task.getTid());
        assertEquals(List.of(
                        TaskMsgStatus.ASSIGNED,
                        TaskMsgStatus.ASSIGNED,
                        TaskMsgStatus.ASSIGNED,
                        TaskMsgStatus.ASSIGNED,
                        TaskMsgStatus.INIT),
                stored.stream().map(TaskMsg::getStatus).collect(Collectors.toList()));
        assertEquals(java.util.Arrays.asList("d1", "d2", "d1", "d2", null),
                stored.stream().map(TaskMsg::getLatestAttemptWorkerId).collect(Collectors.toList()));
    }

    @Test
    void singleWorkerDoesNotExceedBatchSizeWithinOneDispatchRound() {
        Task task = createTask(4);
        task.setBatchSize(2);

        WorkerContext wc1 = workerContext("tk1", "d1");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        List<TaskDispatchBinding> dispatched = listener.onMsgAssign(task, List.of(matched(worker("d1"), wc1)));

        assertEquals(2, dispatched.size());
        List<TaskMsg> stored = storedMessages(task.getTid());
        assertEquals(List.of(
                        TaskMsgStatus.ASSIGNED,
                        TaskMsgStatus.ASSIGNED,
                        TaskMsgStatus.INIT,
                        TaskMsgStatus.INIT),
                stored.stream().map(TaskMsg::getStatus).collect(Collectors.toList()));
        assertEquals(java.util.Arrays.asList("d1", "d1", null, null),
                stored.stream().map(TaskMsg::getLatestAttemptWorkerId).collect(Collectors.toList()));
    }

    @Test
    void finalDispatchRoundCanUseLessThanBatchSizeWhenFewerMessagesRemain() {
        Task task = createTask(3);
        task.setBatchSize(2);

        WorkerContext wc1 = workerContext("tk1", "d1");
        WorkerContext wc2 = workerContext("tk2", "d2");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        List<TaskDispatchBinding> dispatched = listener.onMsgAssign(task, List.of(matched(worker("d1"), wc1), matched(worker("d2"), wc2)));

        assertEquals(3, dispatched.size());
        List<TaskMsg> stored = storedMessages(task.getTid());
        assertEquals(List.of(
                        TaskMsgStatus.ASSIGNED,
                        TaskMsgStatus.ASSIGNED,
                        TaskMsgStatus.ASSIGNED),
                stored.stream().map(TaskMsg::getStatus).collect(Collectors.toList()));
        assertEquals(java.util.Arrays.asList("d1", "d2", "d1"),
                stored.stream().map(TaskMsg::getLatestAttemptWorkerId).collect(Collectors.toList()));
    }

    @Test
    void nullWorkerContextIsHandledGracefully() {
        Task task = createTask(2);
        task.setBatchSize(10);
        assertDoesNotThrow(() -> listener.onMsgAssign(task, List.of(new MatchedWorkerContext(worker("d1"), null))));

        List<TaskMsg> stored = storedMessages(task.getTid());
        assertTrue(stored.stream().allMatch(msg -> msg.getLatestAttemptWorkerContextId() == null));
        assertTrue(stored.stream().allMatch(msg -> msg.getLatestAttemptBatchId() != null && !msg.getLatestAttemptBatchId().isBlank()));
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
        assertTrue(listener.onMsgAssign(task, List.of(matched(worker("d1"), blocked))).isEmpty());

        List<TaskMsg> stored = storedMessages(task.getTid());
        assertEquals(TaskMsgStatus.INIT, stored.get(0).getStatus());
        verify(workerManager).unlockWorker("d1");
        verify(recordService, never()).recordMessageAssignment(
                any(), any(), any(), anyString(), anyString(), any(), anyString(), anyBoolean()
        );
    }

    @Test
    void emptyWorkerListSkipsWithoutMutation() {
        Task task = createTask(2);
        List<String> before = storedMessages(task.getTid()).stream()
                .map(TaskMsg::getMessageId)
                .collect(Collectors.toList());

        assertTrue(listener.onMsgAssign(task, List.of()).isEmpty());

        List<TaskMsg> after = storedMessages(task.getTid());
        assertEquals(before, after.stream().map(TaskMsg::getMessageId).collect(Collectors.toList()));
        assertTrue(after.stream().allMatch(msg -> msg.getStatus() == TaskMsgStatus.INIT));
        verifyNoInteractions(recordService);
    }

    private Task createTask(int messageCount) {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName("task");
        dto.setProject("demoApp");
        dto.setSharedConfig(java.util.Map.of("textContent", "hello", "routingCode", "us"));
        dto.setUserId("agent");
        dto.setBatchSize(1);
        dto.setInputs(IntStream.range(0, messageCount)
                .mapToObj(i -> java.util.Map.<String, Object>of("target", "target-" + i))
                .collect(Collectors.toCollection(ArrayList::new)));
        return taskCommands.createTask(dto);
    }

    private List<TaskMsg> storedMessages(String taskId) {
        long count = taskStorage.countTaskMessages(taskId);
        if (count == 0) {
            return List.of();
        }
        return taskStorage.getTaskMessages(taskId, Math.toIntExact(count));
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

    private SimpleTaskMsgAssignListener newAssignmentListener(TaskManager manager) {
        return new SimpleTaskMsgAssignListener(
                manager,
                workerManager,
                recordService
        );
    }

    private static class NoopTaskScheduler implements TaskScheduler {
        @Override
        public SchedulingResult scheduleTask(Task task) {
            return SchedulingResult.success(List.of());
        }

        @Override
        public List<SchedulingResult> scheduleTasks(List<Task> tasks) {
            return List.of();
        }

        @Override
        public boolean retryTaskMsg(TaskMsg taskMsg) {
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
        public Optional<TaskMsgAttempt> getLatestTaskMessageAttempt(String taskId, String messageId) {
            latestAttemptReadCount.incrementAndGet();
            return super.getLatestTaskMessageAttempt(taskId, messageId);
        }
    }

    private static final class ProjectionPayloadScrubbingStorage extends InMemoryTaskStorage {
        @Override
        public Optional<TaskMsg> getTaskMessage(String taskId, String messageId) {
            Optional<TaskMsg> message = super.getTaskMessage(taskId, messageId);
            message.ifPresent(taskMsg -> taskMsg.setInput(java.util.Map.of()));
            return message;
        }
    }

    private static final class TrackingTaskMessageReadStorage extends InMemoryTaskStorage {
        private final AtomicInteger taskMessageReadCount = new AtomicInteger();

        @Override
        public Optional<TaskMsg> getTaskMessage(String taskId, String messageId) {
            taskMessageReadCount.incrementAndGet();
            return super.getTaskMessage(taskId, messageId);
        }
    }

    private static final class FailingAddAttemptStorage extends InMemoryTaskStorage {
        @Override
        public void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
            throw new IllegalStateException("compatibility attempt projection unavailable");
        }
    }
}


