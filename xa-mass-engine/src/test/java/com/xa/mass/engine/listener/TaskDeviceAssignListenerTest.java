package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.strategy.TaskDeviceMatchingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskDeviceAssignListenerTest {

    private TaskDeviceMatchingStrategy matchingStrategy;
    private DeviceManager deviceManager;
    private TaskMsgAssignListener msgAssignListener;
    private TaskManager taskManager;
    private TaskDeviceAssignListener listener;

    @BeforeEach
    void setUp() {
        matchingStrategy = mock(TaskDeviceMatchingStrategy.class);
        deviceManager = mock(DeviceManager.class);
        msgAssignListener = mock(TaskMsgAssignListener.class);
        taskManager = mock(TaskManager.class);
        listener = new TaskDeviceAssignListener(matchingStrategy, deviceManager, msgAssignListener, taskManager);
    }

    @Test
    void onTaskAssignTransitionsReadyTaskToRunningAndDispatches() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);
        Device device = createDevice("device-1");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(10);
        when(matchingStrategy.matchDevices(same(task), eq(2))).thenReturn(List.of(device));
        when(msgAssignListener.onMsgAssign(same(task), eq(List.of(device)))).thenReturn(List.of(msg("m1", "device-1")));

        assertTrue(listener.onTaskAssign(task));

        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals(1, task.getScheduleDeviceCnt());
        verify(matchingStrategy).matchDevices(same(task), eq(2));
        verify(taskManager).updateTask(same(task));
        verify(msgAssignListener).onMsgAssign(same(task), eq(List.of(device)));
    }

    @Test
    void onTaskAssignUsesRunTaskMinDeviceCountWhenItExceedsCalculatedNeed() {
        Task task = createTask(3, 10, 4, TaskStatus.READY);
        Device device1 = createDevice("device-1");
        Device device2 = createDevice("device-2");
        Device device3 = createDevice("device-3");
        Device device4 = createDevice("device-4");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(3);
        when(matchingStrategy.matchDevices(same(task), eq(4))).thenReturn(List.of(device1, device2, device3, device4));
        when(msgAssignListener.onMsgAssign(same(task), eq(List.of(device1)))).thenReturn(List.of(msg("m1", "device-1")));

        assertTrue(listener.onTaskAssign(task));

        verify(matchingStrategy).matchDevices(same(task), eq(4));
        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals(1, task.getScheduleDeviceCnt());
        verify(taskManager).updateTask(same(task));
        verify(msgAssignListener).onMsgAssign(same(task), eq(List.of(device1)));
        verify(deviceManager).unlockDevice("device-2");
        verify(deviceManager).unlockDevice("device-3");
        verify(deviceManager).unlockDevice("device-4");
    }

    @Test
    void onTaskAssignReturnsWhenNoDeviceMatches() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(10);
        when(matchingStrategy.matchDevices(same(task), eq(2))).thenReturn(List.of());

        assertFalse(listener.onTaskAssign(task));

        assertEquals(TaskStatus.READY, task.getStatus());
        assertEquals(0, task.getScheduleDeviceCnt());
        verify(matchingStrategy).matchDevices(same(task), eq(2));
        verify(taskManager).countPendingDispatchableMessages(task.getTid());
        verifyNoInteractions(msgAssignListener);
    }

    @Test
    void onTaskAssignSkipsDispatchIfTaskLeavesReadyDuringMatching() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);
        Device device = createDevice("device-1");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(10);
        when(matchingStrategy.matchDevices(same(task), eq(2))).thenAnswer(invocation -> {
            task.setStatus(TaskStatus.PAUSED);
            return List.of(device);
        });

        assertFalse(listener.onTaskAssign(task));

        assertEquals(TaskStatus.PAUSED, task.getStatus());
        assertEquals(0, task.getScheduleDeviceCnt());
        verify(matchingStrategy).matchDevices(same(task), eq(2));
        verify(deviceManager).unlockDevice("device-1");
        verify(taskManager, never()).updateTask(task);
        verifyNoInteractions(msgAssignListener);
    }

    @Test
    void onTaskAssignKeepsTaskReadyUntilMinimumDeviceCountIsMet() {
        Task task = createTask(1, 1, 2, TaskStatus.READY);
        Device device = createDevice("device-1");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(1);
        when(matchingStrategy.matchDevices(same(task), eq(2))).thenReturn(List.of(device));

        assertFalse(listener.onTaskAssign(task));

        assertEquals(TaskStatus.READY, task.getStatus());
        assertEquals(0, task.getScheduleDeviceCnt());
        verify(matchingStrategy).matchDevices(same(task), eq(2));
        verify(deviceManager).unlockDevice("device-1");
        verify(taskManager, never()).updateTask(task);
        verifyNoInteractions(msgAssignListener);
    }

    @Test
    void matchDevicesWithRulesDelegatesToInjectedStrategy() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);
        Device device = createDevice("device-1");

        when(matchingStrategy.matchDevices(same(task), eq(3))).thenReturn(List.of(device));

        List<Device> matched = listener.matchDevicesWithRules(task, 3);

        assertEquals(List.of(device), matched);
        verify(matchingStrategy).matchDevices(same(task), eq(3));
    }

    @Test
    void runningTaskCanBeReplenishedWithoutLeavingRunning() {
        Task task = createTask(5, 2, 1, TaskStatus.RUNNING);
        Device device = createDevice("device-1");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(2);
        when(matchingStrategy.matchDevices(same(task), eq(1))).thenReturn(List.of(device));
        when(msgAssignListener.onMsgAssign(same(task), eq(List.of(device)))).thenReturn(List.of(msg("m1", "device-1")));

        assertTrue(listener.onTaskAssign(task));

        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals(1, task.getScheduleDeviceCnt());
        verify(taskManager).updateTask(same(task));
        verify(msgAssignListener).onMsgAssign(same(task), eq(List.of(device)));
    }

    private Task createTask(int targetNumber, int batchSize, int minDeviceCount, TaskStatus status) {
        Task task = new Task();
        task.setTid("task-1");
        task.setTaskRoutingCountryCode("us");
        task.setTaskTargetNumber(targetNumber);
        task.setBatchSize(batchSize);
        task.setRunTaskMinDeviceCnt(minDeviceCount);
        task.setStatus(status);
        return task;
    }

    private Device createDevice(String deviceId) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setDeviceGroupId("pool-a");
        device.setSupportedProjects(List.of("demoApp"));
        return device;
    }

    private com.xa.mass.base.model.TaskMsg msg(String msgId, String deviceId) {
        com.xa.mass.base.model.TaskMsg taskMsg = new com.xa.mass.base.model.TaskMsg(msgId, "task-1", "target");
        taskMsg.setDeviceId(deviceId);
        return taskMsg;
    }
}
