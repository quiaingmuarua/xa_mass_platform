package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.model.MatchedWorkerContext;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.storage.InMemoryTaskStorage;
import com.xa.mass.engine.storage.TaskStorage;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.engine.util.TraceEventLogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SimpleTaskMsgAssignListenerTest {

    private WorkerManager workerManager;
    private AssignmentRecordService recordService;
    private TaskManager taskManager;
    private SimpleTaskMsgAssignListener listener;

    @BeforeEach
    void setUp() {
        workerManager = mock(WorkerManager.class);
        recordService = mock(AssignmentRecordService.class);
        TaskStorage taskStorage = new InMemoryTaskStorage();
        taskManager = new TaskManager(new NoopTaskScheduler(), taskStorage);
        listener = new SimpleTaskMsgAssignListener(taskManager, workerManager, recordService);
    }

    @Test
    void usesPersistedTaskMessagesInsteadOfGeneratingNewOnes() {
        Task task = createTask(3);
        task.setBatchSize(10);
        List<String> storedMsgIds = taskManager.getTaskMessages(task.getTid()).stream()
                .map(TaskMsg::getMsgId)
                .collect(Collectors.toList());
        AtomicReference<List<TaskMsg>> dispatched = new AtomicReference<>();
        listener = new SimpleTaskMsgAssignListener(taskManager, workerManager, recordService, (t, msgs) -> dispatched.set(msgs));

        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        listener.onMsgAssign(task, List.of(matched("d1", "tk1"), matched("d2", "tk2")));

        List<TaskMsg> pushed = dispatched.get();
        assertNotNull(pushed);
        assertEquals(storedMsgIds, pushed.stream().map(TaskMsg::getMsgId).collect(Collectors.toList()));
        assertEquals(List.of("target-0", "target-1", "target-2"),
                pushed.stream().map(msg -> msg.getInput().get("target")).collect(Collectors.toList()));
    }

    @Test
    void assignmentWritesWorkerBatchAndAssignedStatusBackToStorage() {
        Task task = createTask(4);
        task.setBatchSize(10);

        WorkerContext wc1 = workerContext("tk1", "d1");
        WorkerContext wc2 = workerContext("tk2", "d2");
        when(workerManager.updateWorkerContextById(anyString(), any(WorkerContext.class))).thenReturn(true);

        listener.onMsgAssign(task, List.of(matched(worker("d1"), wc1), matched(worker("d2"), wc2)));

        List<TaskMsg> stored = taskManager.getTaskMessages(task.getTid());
        assertEquals(4, stored.size());
        assertEquals(List.of(TaskMsgStatus.ASSIGNED, TaskMsgStatus.ASSIGNED, TaskMsgStatus.ASSIGNED, TaskMsgStatus.ASSIGNED),
                stored.stream().map(TaskMsg::getStatus).collect(Collectors.toList()));
        assertEquals(List.of("d1", "d2", "d1", "d2"),
                stored.stream().map(TaskMsg::getLatestAttemptWorkerId).collect(Collectors.toList()));
        assertEquals(List.of("batch-0", "batch-1", "batch-0", "batch-1"),
                stored.stream().map(TaskMsg::getLatestAttemptBatchId).collect(Collectors.toList()));
        assertTrue(stored.stream().allMatch(msg -> msg.getAssignedTime() != null));
        assertEquals(WorkerContextStatus.OCCUPIED, wc1.getStatus());
        assertEquals(task.getTid(), wc1.getLastBindTaskId());
        assertEquals(WorkerContextStatus.OCCUPIED, wc2.getStatus());
        assertEquals(task.getTid(), wc2.getLastBindTaskId());

        List<TaskMsgAttempt> attempts = stored.stream()
                .map(msg -> taskManager.getTaskMessageAttempts(task.getTid(), msg.getMsgId()))
                .flatMap(List::stream)
                .collect(Collectors.toList());
        assertEquals(4, attempts.size());
        assertTrue(attempts.stream().allMatch(attempt -> attempt.getAttemptNo() == 1));
        assertTrue(attempts.stream().allMatch(attempt -> attempt.getStatus() == TaskMsgAttemptStatus.DISPATCHED));
        assertTrue(attempts.stream().allMatch(attempt -> attempt.getWorkerId() != null));
        assertTrue(attempts.stream().allMatch(attempt -> attempt.getDispatchTime() != null));

        verify(recordService, times(4)).recordMessageAssignment(
                any(), any(), any(), anyString(), anyString(), any(), anyString(), anyBoolean()
        );
        verify(workerManager, times(4)).isLocked(anyString());
        verify(workerManager, times(2)).updateWorkerContextById(anyString(), any(WorkerContext.class));
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
                            && "d1".equals(mdc.get("latestAttemptWorkerId"))
                            && "tk1".equals(mdc.get("latestAttemptWorkerContextId")));
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
            List<TaskMsg> dispatched = listener.onMsgAssign(task, List.of(matched(worker("d1"), wc1), matched(worker("d2"), wc2)));
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

        List<TaskMsg> dispatched = listener.onMsgAssign(task, List.of(matched(worker("d1"), wc1), matched(worker("d2"), wc2)));

        assertEquals(4, dispatched.size());
        List<TaskMsg> stored = taskManager.getTaskMessages(task.getTid());
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

        List<TaskMsg> dispatched = listener.onMsgAssign(task, List.of(matched(worker("d1"), wc1)));

        assertEquals(2, dispatched.size());
        List<TaskMsg> stored = taskManager.getTaskMessages(task.getTid());
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

        List<TaskMsg> dispatched = listener.onMsgAssign(task, List.of(matched(worker("d1"), wc1), matched(worker("d2"), wc2)));

        assertEquals(3, dispatched.size());
        List<TaskMsg> stored = taskManager.getTaskMessages(task.getTid());
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

        List<TaskMsg> stored = taskManager.getTaskMessages(task.getTid());
        assertTrue(stored.stream().allMatch(msg -> msg.getLatestAttemptWorkerContextId() == null));
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

        List<TaskMsg> stored = taskManager.getTaskMessages(task.getTid());
        assertEquals(TaskMsgStatus.INIT, stored.get(0).getStatus());
        verify(workerManager).unlockWorker("d1");
        verify(recordService, never()).recordMessageAssignment(
                any(), any(), any(), anyString(), anyString(), any(), anyString(), anyBoolean()
        );
    }

    @Test
    void emptyWorkerListSkipsWithoutMutation() {
        Task task = createTask(2);
        List<String> before = taskManager.getTaskMessages(task.getTid()).stream()
                .map(TaskMsg::getMsgId)
                .collect(Collectors.toList());

        assertTrue(listener.onMsgAssign(task, List.of()).isEmpty());

        List<TaskMsg> after = taskManager.getTaskMessages(task.getTid());
        assertEquals(before, after.stream().map(TaskMsg::getMsgId).collect(Collectors.toList()));
        assertTrue(after.stream().allMatch(msg -> msg.getStatus() == TaskMsgStatus.INIT));
        verifyNoInteractions(recordService);
    }

    private Task createTask(int messageCount) {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName("task");
        dto.setProject("demoApp");
        dto.setRoutingCode("us");
        dto.setSharedConfig(java.util.Map.of("textContent", "hello"));
        dto.setUserId("agent");
        dto.setBatchSize(1);
        dto.setInputs(IntStream.range(0, messageCount)
                .mapToObj(i -> java.util.Map.<String, Object>of("target", "target-" + i))
                .collect(Collectors.toCollection(ArrayList::new)));
        return taskManager.createTask(dto);
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
        public boolean handleTaskMsgCompletion(TaskMsg taskMsg) {
            return true;
        }

        @Override
        public boolean handleTaskMsgFailure(TaskMsg taskMsg, String errorMessage) {
            return true;
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
}
