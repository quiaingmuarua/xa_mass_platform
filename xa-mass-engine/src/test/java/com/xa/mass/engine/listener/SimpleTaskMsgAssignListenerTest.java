package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TokenStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
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

    private DeviceManager deviceManager;
    private AssignmentRecordService recordService;
    private TaskManager taskManager;
    private SimpleTaskMsgAssignListener listener;

    @BeforeEach
    void setUp() {
        deviceManager = mock(DeviceManager.class);
        recordService = mock(AssignmentRecordService.class);
        TaskStorage taskStorage = new InMemoryTaskStorage();
        taskManager = new TaskManager(new NoopTaskScheduler(), taskStorage);
        listener = new SimpleTaskMsgAssignListener(taskManager, deviceManager, recordService);
    }

    @Test
    void usesPersistedTaskMessagesInsteadOfGeneratingNewOnes() {
        Task task = createTask(3);
        task.setBatchSize(10);
        List<String> storedMsgIds = taskManager.getTaskMessages(task.getTid()).stream()
                .map(TaskMsg::getMsgId)
                .collect(Collectors.toList());
        AtomicReference<List<TaskMsg>> dispatched = new AtomicReference<>();
        listener = new SimpleTaskMsgAssignListener(taskManager, deviceManager, recordService, (t, msgs) -> dispatched.set(msgs));

        when(deviceManager.getToken("d1")).thenReturn(token("tk1", "d1"));
        when(deviceManager.getToken("d2")).thenReturn(token("tk2", "d2"));
        when(deviceManager.updateToken(anyString(), any(Token.class))).thenReturn(true);

        listener.onMsgAssign(task, List.of(device("d1"), device("d2")));

        List<TaskMsg> pushed = dispatched.get();
        assertNotNull(pushed);
        assertEquals(storedMsgIds, pushed.stream().map(TaskMsg::getMsgId).collect(Collectors.toList()));
        assertEquals(List.of("target-0", "target-1", "target-2"),
                pushed.stream().map(TaskMsg::getTarget).collect(Collectors.toList()));
    }

    @Test
    void assignmentWritesDeviceBatchAndSentStatusBackToStorage() {
        Task task = createTask(4);
        task.setBatchSize(10);

        Token token1 = token("tk1", "d1");
        Token token2 = token("tk2", "d2");
        when(deviceManager.getToken("d1")).thenReturn(token1);
        when(deviceManager.getToken("d2")).thenReturn(token2);
        when(deviceManager.updateToken(anyString(), any(Token.class))).thenReturn(true);

        listener.onMsgAssign(task, List.of(device("d1"), device("d2")));

        List<TaskMsg> stored = taskManager.getTaskMessages(task.getTid());
        assertEquals(4, stored.size());
        assertEquals(List.of(TaskMsgStatus.SENT, TaskMsgStatus.SENT, TaskMsgStatus.SENT, TaskMsgStatus.SENT),
                stored.stream().map(TaskMsg::getStatus).collect(Collectors.toList()));
        assertEquals(List.of("d1", "d2", "d1", "d2"),
                stored.stream().map(TaskMsg::getDeviceId).collect(Collectors.toList()));
        assertEquals(List.of("batch-0", "batch-1", "batch-0", "batch-1"),
                stored.stream().map(TaskMsg::getBatchId).collect(Collectors.toList()));
        assertEquals(TokenStatus.SENDING, token1.getStatus());
        assertEquals(task.getTid(), token1.getLastBindTaskId());
        assertEquals(TokenStatus.SENDING, token2.getStatus());
        assertEquals(task.getTid(), token2.getLastBindTaskId());

        verify(recordService, times(4)).recordMessageAssignment(
                any(), any(), any(), anyString(), anyString(), any(), anyString(), anyBoolean()
        );
        verify(deviceManager, times(4)).isLocked(anyString());
        verify(deviceManager, times(2)).updateToken(anyString(), any(Token.class));
    }

    @Test
    void assignmentRespectsPerDeviceBatchSizeAndLeavesRemainingMessagesPending() {
        Task task = createTask(5);
        task.setBatchSize(2);

        Token token1 = token("tk1", "d1");
        Token token2 = token("tk2", "d2");
        when(deviceManager.getToken("d1")).thenReturn(token1);
        when(deviceManager.getToken("d2")).thenReturn(token2);
        when(deviceManager.updateToken(anyString(), any(Token.class))).thenReturn(true);

        List<TaskMsg> dispatched = listener.onMsgAssign(task, List.of(device("d1"), device("d2")));

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
                stored.stream().map(TaskMsg::getDeviceId).collect(Collectors.toList()));
    }

    @Test
    void nullTokenIsHandledGracefully() {
        Task task = createTask(2);
        task.setBatchSize(10);
        when(deviceManager.getToken("d1")).thenReturn(null);

        assertDoesNotThrow(() -> listener.onMsgAssign(task, List.of(device("d1"))));

        List<TaskMsg> stored = taskManager.getTaskMessages(task.getTid());
        assertTrue(stored.stream().allMatch(msg -> msg.getTokenId() == null));
        verify(recordService, times(2)).recordMessageAssignment(
                any(), any(), isNull(), anyString(), anyString(), any(), anyString(), anyBoolean()
        );
        verify(deviceManager, times(2)).isLocked("d1");
    }

    @Test
    void nonDispatchableTokenSkipsDeviceAndUnlocksIt() {
        Task task = createTask(1);
        Token blocked = token("tk-blocked", "d1");
        blocked.block();
        when(deviceManager.getToken("d1")).thenReturn(blocked);

        assertTrue(listener.onMsgAssign(task, List.of(device("d1"))).isEmpty());

        List<TaskMsg> stored = taskManager.getTaskMessages(task.getTid());
        assertEquals(TaskMsgStatus.INIT, stored.get(0).getStatus());
        verify(deviceManager).unlockDevice("d1");
        verify(recordService, never()).recordMessageAssignment(
                any(), any(), any(), anyString(), anyString(), any(), anyString(), anyBoolean()
        );
    }

    @Test
    void emptyDeviceListSkipsWithoutMutation() {
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
        dto.setTextContent("hello");
        dto.setUserId("agent");
        dto.setBatchSize(1);
        dto.setTargetList(IntStream.range(0, messageCount)
                .mapToObj(i -> "target-" + i)
                .collect(Collectors.toCollection(ArrayList::new)));
        return taskManager.createTask(dto);
    }

    private Device device(String id) {
        Device d = new Device();
        d.setDeviceId(id);
        return d;
    }

    private Token token(String tokenId, String deviceId) {
        Token t = new Token();
        t.setTokenId(tokenId);
        t.setDeviceId(deviceId);
        return t;
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
