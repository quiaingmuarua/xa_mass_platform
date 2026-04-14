package com.xa.mass.engine.strategy;

import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.device.DeviceStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TokenStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleType;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.storage.InMemoryDeviceStorage;
import com.xa.mass.engine.storage.InMemoryRuleStorage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedTaskDeviceMatchingStrategyTest {

    @Test
    void matchesDeviceUsingTokenAttributesRule() {
        DeviceManager deviceManager = new DeviceManager(new InMemoryDeviceStorage());
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskDeviceMatchingStrategy strategy =
                new RuleBasedTaskDeviceMatchingStrategy(ruleManager, deviceManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_device_check", "isDeviceAvailable == true && isDeviceLocked == false"),
                rule("token_status_check", "isTokenAllocatable == true && isTokenAvailable == true"),
                rule("app_support_check", "supportsProject == true"),
                rule("token_attribute_country", "tokenAttributes['country'] == taskRoutingCountryCode")
        ));

        Task task = new Task();
        task.setTid("task-1");
        task.setProject(Project.DEMO_APP);
        task.setTaskRoutingCountryCode("us");
        task.setStatus(TaskStatus.READY);

        Device matchingDevice = device("device-us", "pool-east");
        Device nonMatchingDevice = device("device-gb", "pool-west");
        deviceManager.addDevice(matchingDevice);
        deviceManager.addDevice(nonMatchingDevice);

        deviceManager.addToken(matchingDevice.getDeviceId(), token("device-us", "token-us", "shared", "us"));
        deviceManager.addToken(nonMatchingDevice.getDeviceId(), token("device-gb", "token-gb", "shared", "gb"));

        List<Device> matched = strategy.matchDevices(task, 2);

        assertEquals(1, matched.size());
        assertEquals("device-us", matched.get(0).getDeviceId());
        AssignmentRecord record = recordService.getRecordsByTaskId("task-1").stream()
                .filter(item -> "device-us".equals(item.getDeviceId()))
                .findFirst()
                .orElseThrow();
        assertTrue(record.getDeviceSnapshot().isDeviceLocked());
    }

    @Test
    void recordsRuntimeLockStateForPreLockedDevices() {
        DeviceManager deviceManager = new DeviceManager(new InMemoryDeviceStorage());
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskDeviceMatchingStrategy strategy =
                new RuleBasedTaskDeviceMatchingStrategy(ruleManager, deviceManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_device_check", "isDeviceAvailable == true && isDeviceLocked == false"),
                rule("token_status_check", "isTokenAllocatable == true && isTokenAvailable == true"),
                rule("app_support_check", "supportsProject == true")
        ));

        Task task = new Task();
        task.setTid("task-locked");
        task.setProject(Project.DEMO_APP);
        task.setStatus(TaskStatus.READY);

        Device device = device("device-locked", "pool-east");
        deviceManager.addDevice(device);
        deviceManager.addToken(device.getDeviceId(), token("device-locked", "token-locked", "shared", "us"));
        assertTrue(deviceManager.tryLockDevice(device.getDeviceId()));

        List<Device> matched = strategy.matchDevices(task, 1);

        assertTrue(matched.isEmpty());
        AssignmentRecord record = recordService.getRecordsByTaskId("task-locked").stream()
                .filter(item -> "device-locked".equals(item.getDeviceId()))
                .findFirst()
                .orElseThrow();
        assertTrue(record.getDeviceSnapshot().isDeviceLocked());
    }

    private RuleDefinition rule(String id, String content) {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(id);
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent(content);
        return rule;
    }

    private Device device(String deviceId, String deviceGroupId) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setDeviceGroupId(deviceGroupId);
        device.setStatus(DeviceStatus.ONLINE);
        device.setSupportedProjects(List.of(Project.DEMO_APP));
        return device;
    }

    private Token token(String deviceId, String tokenId, String channel, String country) {
        Token token = new Token();
        token.setDeviceId(deviceId);
        token.setTokenId(tokenId);
        token.setChannel(channel);
        token.setStatus(TokenStatus.LOGIN_READY);
        token.setAttributes(Map.of("country", country));
        return token;
    }
}
