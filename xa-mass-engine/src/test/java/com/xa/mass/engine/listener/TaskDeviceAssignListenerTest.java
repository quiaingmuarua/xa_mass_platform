package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.service.AssignmentRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TaskDeviceAssignListener 测试
 */
public class TaskDeviceAssignListenerTest {

    @Mock
    private RuleManager<Map<String, Object>> ruleManager;

    @Mock
    private DeviceManager deviceManager;

    @Mock
    private TaskMsgAssignListener msgAssignListener;

    @Mock
    private AssignmentRecordService recordService;

    private TaskDeviceAssignListener listener;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        listener = new TaskDeviceAssignListener(ruleManager, deviceManager, msgAssignListener, recordService);
    }

    @Test
    public void testMatchDevicesWithRules_Success() {
        // 准备测试数据
        Task task = createTestTask();
        Device device = createTestDevice();
        Token token = createTestToken();
        List<RuleDefinition> rules = createTestRules();

        // Mock 行为
        when(deviceManager.getDevicesByCountry("us")).thenReturn(Arrays.asList(device));
        when(deviceManager.getToken("device-1")).thenReturn(token);
        when(ruleManager.getDefaultRules()).thenReturn(rules);
        when(ruleManager.evaluateDefaultRules(any())).thenReturn(Arrays.asList("rule1", "rule2"));
        when(deviceManager.tryLockDevice("device-1")).thenReturn(true);

        // 执行测试
        List<Device> result = listener.matchDevicesWithRules(task, 1);

        // 验证结果
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("device-1", result.get(0).getDeviceId());

        // 验证调用
        verify(deviceManager).getDevicesByCountry("us");
        verify(deviceManager).getToken("device-1");
        verify(ruleManager, times(2)).getDefaultRules();
        verify(ruleManager).evaluateDefaultRules(any());
        verify(deviceManager).tryLockDevice("device-1");
        verify(recordService).recordDeviceAssignment(eq(task), eq(device), eq(token),
                eq(com.xa.mass.base.enums.assignment.AssignmentResult.SUCCESS),
                anyString(), anyList(), anyMap());
    }

    @Test
    public void testMatchDevicesWithRules_DeviceLocked() {
        // 准备测试数据
        Task task = createTestTask();
        Device device = createTestDevice();
        Token token = createTestToken();
        List<RuleDefinition> rules = createTestRules();

        // Mock 行为
        when(deviceManager.getDevicesByCountry("us")).thenReturn(Arrays.asList(device));
        when(deviceManager.getToken("device-1")).thenReturn(token);
        when(ruleManager.getDefaultRules()).thenReturn(rules);
        when(ruleManager.evaluateDefaultRules(any())).thenReturn(Arrays.asList("rule1", "rule2"));
        when(deviceManager.tryLockDevice("device-1")).thenReturn(false); // 设备被锁定

        // 执行测试
        List<Device> result = listener.matchDevicesWithRules(task, 1);

        // 验证结果
        assertNotNull(result);
        assertEquals(0, result.size()); // 没有匹配到设备

        // 验证调用
        verify(recordService).recordDeviceAssignment(eq(task), eq(device), eq(token),
                eq(com.xa.mass.base.enums.assignment.AssignmentResult.CONFLICT),
                anyString(), anyList(), anyMap());
    }

    @Test
    public void testMatchDevicesWithRules_RuleNotMatch() {
        // 准备测试数据
        Task task = createTestTask();
        Device device = createTestDevice();
        Token token = createTestToken();
        List<RuleDefinition> rules = createTestRules();

        // Mock 行为
        when(deviceManager.getDevicesByCountry("us")).thenReturn(Arrays.asList(device));
        when(deviceManager.getToken("device-1")).thenReturn(token);
        when(ruleManager.getDefaultRules()).thenReturn(rules);
        when(ruleManager.evaluateDefaultRules(any())).thenReturn(Arrays.asList("rule1")); // 只通过一个规则

        // 执行测试
        List<Device> result = listener.matchDevicesWithRules(task, 1);

        // 验证结果
        assertNotNull(result);
        assertEquals(0, result.size()); // 没有匹配到设备

        // 验证调用
        verify(recordService).recordDeviceAssignment(eq(task), eq(device), eq(token),
                eq(com.xa.mass.base.enums.assignment.AssignmentResult.RULE_NOT_MATCH),
                anyString(), anyList(), anyMap());
    }

    @Test
    public void testMatchDevicesWithRules_MaxDeviceCountReached() {
        // 准备测试数据
        Task task = createTestTask();
        Device device1 = createTestDevice("device-1");
        Device device2 = createTestDevice("device-2");
        Token token = createTestToken();
        List<RuleDefinition> rules = createTestRules();

        // Mock 行为
        when(deviceManager.getDevicesByCountry("us")).thenReturn(Arrays.asList(device1, device2));
        when(deviceManager.getToken(anyString())).thenReturn(token);
        when(ruleManager.getDefaultRules()).thenReturn(rules);
        when(ruleManager.evaluateDefaultRules(any())).thenReturn(Arrays.asList("rule1", "rule2"));
        when(deviceManager.tryLockDevice(anyString())).thenReturn(true);

        // 执行测试 - 限制最大设备数为1
        List<Device> result = listener.matchDevicesWithRules(task, 1);

        // 验证结果
        assertNotNull(result);
        assertEquals(1, result.size()); // 只匹配到一个设备

        // 验证只尝试锁定第一个设备
        verify(deviceManager).tryLockDevice("device-1");
        verify(deviceManager, never()).tryLockDevice("device-2");
    }

    @Test
    public void testOnTaskAssignTransitionsReadyTaskToRunning() {
        Task task = createTestTask();
        task.setStatus(TaskStatus.READY);
        Device device = createTestDevice();
        Token token = createTestToken();
        List<RuleDefinition> rules = createTestRules();

        when(deviceManager.getDevicesByCountry("us")).thenReturn(Arrays.asList(device));
        when(deviceManager.getToken("device-1")).thenReturn(token);
        when(ruleManager.getDefaultRules()).thenReturn(rules);
        when(ruleManager.evaluateDefaultRules(any())).thenReturn(Arrays.asList("rule1", "rule2"));
        when(deviceManager.tryLockDevice("device-1")).thenReturn(true);

        listener.onTaskAssign(task);

        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals(1, task.getScheduleDeviceCnt());
        verify(msgAssignListener).onMsgAssign(same(task), argThat(devices ->
                devices.size() == 1 && "device-1".equals(devices.get(0).getDeviceId())));
    }

    private Task createTestTask() {
        Task task = new Task();
        task.setTid("task-1");
        task.setTaskCountry("us");
        task.setTaskInitNumber(10);
        task.setBatchSize(5);
        task.setRunTaskMinDeviceCnt(1);
        return task;
    }

    private Device createTestDevice() {
        return createTestDevice("device-1");
    }

    private Device createTestDevice(String deviceId) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setGroupId("us");
        device.setSupportedProjects(Arrays.asList(Project.DEMO_APP));
        return device;
    }

    private Token createTestToken() {
        Token token = new Token();
        token.setTokenId("token-1");
        token.setDeviceId("device-1");
        token.setChannel("us");
        return token;
    }

    private List<RuleDefinition> createTestRules() {
        List<RuleDefinition> rules = new ArrayList<>();

        RuleDefinition rule1 = new RuleDefinition();
        rule1.setId("rule1");
        rule1.setDesc("测试规则1");
        rule1.setContent("device.groupId == 'us'");
        rules.add(rule1);

        RuleDefinition rule2 = new RuleDefinition();
        rule2.setId("rule2");
        rule2.setDesc("测试规则2");
        rule2.setContent("token.channel == 'us'");
        rules.add(rule2);

        return rules;
    }
} 
