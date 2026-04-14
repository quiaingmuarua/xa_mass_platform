package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.strategy.TaskDeviceMatchingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskDeviceAssignListenerTest {

    private TaskDeviceMatchingStrategy matchingStrategy;
    private TaskMsgAssignListener msgAssignListener;
    private TaskManager taskManager;
    private TaskDeviceAssignListener listener;

    @BeforeEach
    void setUp() {
        matchingStrategy = mock(TaskDeviceMatchingStrategy.class);
        msgAssignListener = mock(TaskMsgAssignListener.class);
        taskManager = mock(TaskManager.class);
        listener = new TaskDeviceAssignListener(matchingStrategy, msgAssignListener, taskManager);
    }

    @Test
    void onTaskAssignTransitionsReadyTaskToRunningAndDispatches() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);
        Device device = createDevice("device-1");

        when(matchingStrategy.matchDevices(same(task), eq(2))).thenReturn(List.of(device));

        listener.onTaskAssign(task);

        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals(1, task.getScheduleDeviceCnt());
        verify(matchingStrategy).matchDevices(same(task), eq(2));
        verify(taskManager).updateTask(same(task));
        verify(msgAssignListener).onMsgAssign(same(task), eq(List.of(device)));
    }

    @Test
    void onTaskAssignUsesRunTaskMinDeviceCountWhenItExceedsCalculatedNeed() {
        Task task = createTask(3, 10, 4, TaskStatus.READY);

        when(matchingStrategy.matchDevices(same(task), eq(4))).thenReturn(List.of(createDevice("device-1")));

        listener.onTaskAssign(task);

        verify(matchingStrategy).matchDevices(same(task), eq(4));
    }

    @Test
    void onTaskAssignReturnsWhenNoDeviceMatches() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);

        when(matchingStrategy.matchDevices(same(task), eq(2))).thenReturn(List.of());

        listener.onTaskAssign(task);

        assertEquals(TaskStatus.READY, task.getStatus());
        assertEquals(0, task.getScheduleDeviceCnt());
        verify(matchingStrategy).matchDevices(same(task), eq(2));
        verifyNoInteractions(taskManager, msgAssignListener);
    }

    @Test
    void onTaskAssignSkipsDispatchIfTaskLeavesReadyDuringMatching() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);
        Device device = createDevice("device-1");

        when(matchingStrategy.matchDevices(same(task), eq(2))).thenAnswer(invocation -> {
            task.setStatus(TaskStatus.PAUSED);
            return List.of(device);
        });

        listener.onTaskAssign(task);

        assertEquals(TaskStatus.PAUSED, task.getStatus());
        assertEquals(0, task.getScheduleDeviceCnt());
        verify(matchingStrategy).matchDevices(same(task), eq(2));
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
        device.setSupportedProjects(List.of(Project.DEMO_APP));
        return device;
    }
}
