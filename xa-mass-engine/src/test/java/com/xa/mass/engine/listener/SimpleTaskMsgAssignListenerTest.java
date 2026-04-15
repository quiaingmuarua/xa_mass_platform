package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.storage.InMemoryTaskStorage;
import com.xa.mass.engine.storage.TaskStorage;
import com.xa.mass.engine.strategy.TaskScheduler;
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

        when(workerManager.getWorkerContext("d1")).thenReturn(workerContext("tk1", "d1"));
        when(workerManager.getWorkerContext("d2")).thenReturn(workerContext("tk2", "d2"));
        when(workerManager.updateWorkerContext(anyString(), any(WorkerContext.class))).thenReturn(true);

        listener.onMsgAssign(task, List.of(worker("d1"), worker("d2")));

        List<TaskMsg> pushed = dispatched.get();
        assertNotNull(pushed);
        assertEquals(storedMsgIds, pushed.stream().map(TaskMsg::getMsgId).collect(Collectors.toList()));
        assertEquals(List.of("target-0", "target-1", "target-2"),
                pushed.stream().map(TaskMsg::getTarget).collect(Collectors.toList()));
    }

    @Test
    void assignmentWritesWorkerBatchAndSentStatusBackToStorage() {
        Task task = createTask(4);
        task.setBatchSize(10);

        WorkerContext wc1 = workerContext("tk1", "d1");
        WorkerContext wc2 = workerContext("tk2", "d2");
        when(workerManager.getWorkerContext("d1")).thenReturn(wc1);
        when(workerManager.getWorkerContext("d2")).thenReturn(wc2);
        when(workerManager.updateWorkerContext(anyString(), any(WorkerContext.class))).thenReturn(true);

        listener.onMsgAssign(task, List.of(worker("d1"), worker("d2")));

        List<TaskMsg> stored = taskManager.getTaskMessages(task.getTid());
        assertEquals(4, stored.size());
        assertEquals(List.of(TaskMsgStatus.SENT, TaskMsgStatus.SENT, TaskMsgStatus.SENT, TaskMsgStatus.SENT),
                stored.stream().map(TaskMsg::getStatus).collect(Collectors.toList()));
        assertEquals(List.of("d1", "d2", "d1", "d2"),
                stored.stream().map(TaskMsg::getWorkerId).collect(Collectors.toList()));
        assertEquals(List.of("batch-0", "batch-1", "batch-0", "batch-1"),
                stored.stream().map(TaskMsg::getBatchId).collect(Collectors.toList()));
        assertEquals(WorkerContextStatus.OCCUPIED, wc1.getStatus());
        assertEquals(task.getTid(), wc1.getLastBindTaskId());
        assertEquals(WorkerContextStatus.OCCUPIED, wc2.getStatus());
        assertEquals(task.getTid(), wc2.getLastBindTaskId());

        verify(recordService, times(4)).recordMessageAssignment(
                any(), any(), any(), anyString(), anyString(), any(), anyString(), anyBoolean()
        );
        verify(workerManager, times(4)).isLocked(anyString());
        verify(workerManager, times(2)).updateWorkerContext(anyString(), any(WorkerContext.class));
    }

    @Test
    void assignmentRespectsPerWorkerBatchSizeAndLeavesRemainingMessagesPending() {
        Task task = createTask(5);
        task.setBatchSize(2);

        WorkerContext wc1 = workerContext("tk1", "d1");
        WorkerContext wc2 = workerContext("tk2", "d2");
        when(workerManager.getWorkerContext("d1")).thenReturn(wc1);
        when(workerManager.getWorkerContext("d2")).thenReturn(wc2);
        when(workerManager.updateWorkerContext(anyString(), any(WorkerContext.class))).thenReturn(true);

        List<TaskMsg> dispatched = listener.onMsgAssign(task, List.of(worker("d1"), worker("d2")));

        assertEquals(4, dispatched.size());
        List<TaskMsg> stored = taskManager.getTaskMessages(task.getTid());
        assertEquals(List.of(
                        TaskMsgStatus.SENT,
                        TaskMsgStatus.SENT,
                        TaskMsgStatus.SENT,
                        TaskMsgStatus.SENT,
                        TaskMsgStatus.INIT),
                stored.stream().map(TaskMsg::getStatus).collect(Collectors.toList()));
        assertEquals(java.util.Arrays.asList("d1", "d2", "d1", "d2", null),
                stored.stream().map(TaskMsg::getWorkerId).collect(Collectors.toList()));
    }

    @Test
    void nullWorkerContextIsHandledGracefully() {
        Task task = createTask(2);
        task.setBatchSize(10);
        when(workerManager.getWorkerContext("d1")).thenReturn(null);

        assertDoesNotThrow(() -> listener.onMsgAssign(task, List.of(worker("d1"))));

        List<TaskMsg> stored = taskManager.getTaskMessages(task.getTid());
        assertTrue(stored.stream().allMatch(msg -> msg.getWorkerContextId() == null));
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
        when(workerManager.getWorkerContext("d1")).thenReturn(blocked);

        assertTrue(listener.onMsgAssign(task, List.of(worker("d1"))).isEmpty());

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
        dto.setCountryCode("us");
        dto.setSharedConfig(java.util.Map.of("textContent", "hello"));
        dto.setUserId("agent");
        dto.setBatchSize(1);
        dto.setTargetList(IntStream.range(0, messageCount)
                .mapToObj(i -> "target-" + i)
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
