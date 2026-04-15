package com.xa.mass.engine.example;

import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.device.DeviceStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TokenStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.model.DeviceMatchContext;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.storage.DeviceStorage;
import com.xa.mass.engine.storage.TaskStorageFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Simple debugging entry for rule-based device matching.
 */
public class RuleDebugExample {

    private static final Logger logger = LoggerFactory.getLogger(RuleDebugExample.class);

    public static void main(String[] args) {
        System.out.println("=== Rule Debug Example ===");

        DeviceStorage deviceStorage = TaskStorageFactory.createDefaultDeviceStorage();
        DeviceManager deviceManager = new DeviceManager(deviceStorage);
        RuleManager<Map<String, Object>> ruleManager = RuleManagerFactory.getDefaultRuleManager();

        generateTestData(deviceManager);
        Task testTask = createTestTask();

        List<Device> candidates = deviceManager.getAllDevices();
        System.out.println("Candidate device count: " + candidates.size());

        for (Device device : candidates) {
            debugDeviceEvaluation(device, testTask, deviceManager, ruleManager);
        }
    }

    private static void generateTestData(DeviceManager deviceManager) {
        String[] countries = {"us", "gb", "ca"};

        for (int i = 0; i < 10; i++) {
            String country = countries[i % countries.length];

            Device device = new Device();
            device.setDeviceId("device-" + i);
            device.setDeviceGroupId(country);
            device.setStatus(DeviceStatus.ONLINE);
            device.setAgentVersion("1.0." + (i % 5));
            device.setSupportedProjects(Arrays.asList(Project.DEMO_APP));

            Token token = new Token();
            token.setTokenId("token-" + i);
            token.setDeviceId(device.getDeviceId());
            token.setStatus(TokenStatus.IDLE);
            token.setChannel(country);
            token.setAttributes(Map.of("country", country));

            deviceManager.addDevice(device);
            deviceManager.addToken(device.getDeviceId(), token);

            logger.info("Device {} supports projects: {}", device.getDeviceId(),
                    device.getSupportedProjects().stream()
                            .map(Project::getCode)
                            .collect(Collectors.joining(", ")));
        }

        System.out.println("Generated 10 test devices and tokens");
    }

    private static Task createTestTask() {
        Task task = new Task();
        task.setTid("test-task-001");
        task.setTaskName("routing-country-debug");
        task.setProject("demoApp");
        task.setTaskRoutingCountryCode("us");
        task.setStatus(TaskStatus.READY);
        task.setTaskTargetNumber(100);
        task.setBatchSize(10);
        task.setRunTaskMinDeviceCnt(5);
        return task;
    }

    private static void debugDeviceEvaluation(Device device, Task task, DeviceManager deviceManager,
                                              RuleManager<Map<String, Object>> ruleManager) {
        System.out.println("\n=== Debugging device: " + device.getDeviceId() + " ===");

        Token token = deviceManager.getToken(device.getDeviceId());
        DeviceMatchContext matchContext = new DeviceMatchContext(device, token, task, deviceManager);

        System.out.println("Device:");
        System.out.println("  - id: " + device.getDeviceId());
        System.out.println("  - deviceGroupId: " + device.getDeviceGroupId());
        System.out.println("  - status: " + device.getStatus());
        System.out.println("  - agentVersion: " + device.getAgentVersion());
        System.out.println("  - supportedProjects: " + device.getSupportedProjects());

        if (token != null) {
            System.out.println("Token:");
            System.out.println("  - id: " + token.getTokenId());
            System.out.println("  - status: " + token.getStatus());
            System.out.println("  - channel: " + token.getChannel());
            System.out.println("  - attributes: " + token.getAttributes());
        } else {
            System.out.println("Token: null");
        }

        System.out.println("Task:");
        System.out.println("  - id: " + task.getTid());
        System.out.println("  - project: " + task.getProjectCode());
        System.out.println("  - routingCountryCode: " + task.getTaskRoutingCountryCode());

        Map<String, Object> context = matchContext.getContext();
        System.out.println("Computed context:");
        System.out.println("  - appCount: " + context.get("appCount"));
        System.out.println("  - supportsProject: " + context.get("supportsProject"));
        System.out.println("  - deviceGroupIdEqualsRoutingCountry: " + context.get("deviceGroupIdEqualsRoutingCountry"));
        System.out.println("  - tokenChannelMatchesRoutingCountry: " + context.get("tokenChannelMatchesRoutingCountry"));
        System.out.println("  - tokenAttributeCountryMatchesRoutingCountry: " + context.get("tokenAttributeCountryMatchesRoutingCountry"));

        List<RuleDefinition> rules = ruleManager.getDefaultRules();
        System.out.println("\nRule evaluation:");

        int passedRules = 0;
        for (RuleDefinition rule : rules) {
            try {
                boolean result = ruleManager.evaluate(rule, context);
                System.out.println("  - " + rule.getId() + " (" + rule.getDesc() + "): " + (result ? "PASS" : "FAIL"));
                System.out.println("    expression: " + rule.getContent());
                if (result) {
                    passedRules++;
                }
            } catch (Exception e) {
                System.out.println("  - " + rule.getId() + ": ERROR - " + e.getMessage());
            }
        }

        System.out.println("Summary: " + passedRules + "/" + rules.size() + " rules passed");
    }
}
