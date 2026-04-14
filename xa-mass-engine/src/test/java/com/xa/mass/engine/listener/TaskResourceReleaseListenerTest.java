package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TokenStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskResourceReleaseListenerTest {

    private TaskManager taskManager;
    private DeviceManager deviceManager;
    private TaskResourceReleaseListener listener;
    private Consumer<Task> dispatchRequester;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        taskManager = mock(TaskManager.class);
        deviceManager = mock(DeviceManager.class);
        dispatchRequester = mock(Consumer.class);
        listener = new TaskResourceReleaseListener(taskManager, deviceManager, dispatchRequester);
    }

    @Test
    void terminalTaskReleasesTokenAndUnlocksDevice() {
        Task task = new Task();
        task.setTid("task-1");

        TaskMsg msg = new TaskMsg("msg-1", "task-1", "target-a");
        msg.setDeviceId("device-1");
        msg.setTokenId("token-1");

        Token token = new Token("token-1", "device-1", "us");
        token.bindToTask("task-1");
        token.startSending();

        when(taskManager.getTaskMessages("task-1")).thenReturn(List.of(msg));
        when(deviceManager.getToken("device-1")).thenReturn(token);
        when(deviceManager.updateToken("device-1", token)).thenReturn(true);

        listener.onTaskTerminal(task);

        verify(deviceManager).updateToken("device-1", token);
        verify(deviceManager).unlockDevice("device-1");
    }

    @Test
    void listenerDoesNotReleaseTokenOwnedByAnotherTask() {
        Task task = new Task();
        task.setTid("task-1");

        TaskMsg msg = new TaskMsg("msg-1", "task-1", "target-a");
        msg.setDeviceId("device-1");
        msg.setTokenId("token-1");

        Token token = new Token("token-1", "device-1", "us");
        token.bindToTask("other-task");
        token.startSending();

        when(taskManager.getTaskMessages("task-1")).thenReturn(List.of(msg));
        when(deviceManager.getToken("device-1")).thenReturn(token);

        listener.onTaskTerminal(task);

        verify(deviceManager, never()).updateToken("device-1", token);
        verify(deviceManager).unlockDevice("device-1");
    }

    @Test
    void finalMessageReleasesIdleDeviceAndRequestsReplenishment() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg finalMsg = new TaskMsg("msg-1", "task-1", "target-a");
        finalMsg.setDeviceId("device-1");
        finalMsg.setTokenId("token-1");
        finalMsg.setStatus(TaskMsgStatus.SUCCESS);

        Token token = new Token("token-1", "device-1", "us");
        token.bindToTask("task-1");
        token.startSending();

        when(taskManager.getTaskMessages("task-1")).thenReturn(List.of(finalMsg));
        when(taskManager.hasPendingDispatchableMessages("task-1")).thenReturn(true);
        when(deviceManager.getToken("device-1")).thenReturn(token);
        when(deviceManager.updateToken("device-1", token)).thenReturn(true);

        listener.onTaskMessageFinal(task, finalMsg);

        verify(deviceManager).updateToken("device-1", token);
        verify(deviceManager).unlockDevice("device-1");
        verify(dispatchRequester).accept(same(task));
    }

    @Test
    void finalMessageKeepsDeviceLockedWhenAnotherMessageIsStillProcessing() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg finalMsg = new TaskMsg("msg-1", "task-1", "target-a");
        finalMsg.setDeviceId("device-1");
        finalMsg.setTokenId("token-1");
        finalMsg.setStatus(TaskMsgStatus.SUCCESS);

        TaskMsg runningMsg = new TaskMsg("msg-2", "task-1", "target-b");
        runningMsg.setDeviceId("device-1");
        runningMsg.setTokenId("token-1");
        runningMsg.setStatus(TaskMsgStatus.RUNNING);

        when(taskManager.getTaskMessages("task-1")).thenReturn(List.of(finalMsg, runningMsg));

        listener.onTaskMessageFinal(task, finalMsg);

        verify(deviceManager, never()).unlockDevice("device-1");
        verify(dispatchRequester, never()).accept(same(task));
    }
}
