package com.xa.mass.engine.example;

import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.model.DeviceMatchContext;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.storage.DeviceStorage;
import com.xa.mass.engine.storage.TaskStorageFactory;
import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Task;
import com.xa.mass.eventbus.model.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 规则调试示例
 * 帮助诊断设备匹配规则的问题
 */
public class RuleDebugExample {

    private static final Logger logger = LoggerFactory.getLogger(RuleDebugExample.class);

    public static void main(String[] args) {
        System.out.println("=== 规则调试示例 ===");

        // 创建设备管理器
        DeviceStorage deviceStorage = TaskStorageFactory.createDefaultDeviceStorage();
        DeviceManager deviceManager = new DeviceManager(deviceStorage);

        // 创建规则管理器
        RuleManager<Map<String, Object>> ruleManager = RuleManagerFactory.getDefaultRuleManager();

        // 生成测试数据
        generateTestData(deviceManager);

        // 创建测试任务
        Task testTask = createTestTask();

        // 获取候选设备
        List<Device> candidates = deviceManager.getDevicesByCountry(testTask.getTaskCountry());
        System.out.println("候选设备数量: " + candidates.size());

        // 调试每个设备的规则评估
        for (Device device : candidates) {
            debugDeviceEvaluation(device, testTask, deviceManager, ruleManager);
        }
    }

    private static void generateTestData(DeviceManager deviceManager) {
        // 生成一些测试设备
        String[] countries = {"us", "gb", "ca"};
        String[] projects = {"demoApp", "testApp", "prodApp"};

        for (int i = 0; i < 10; i++) {
            Device device = new Device();
            device.setDeviceId("device-" + i);
            device.setGroupId(countries[i % countries.length]);
            device.setStatus(com.xa.mass.eventbus.enums.device.DeviceStatus.ONLINE);
            device.setAgentVersion("1.0." + (i % 5));
            device.setSupportedApps(java.util.Arrays.asList(projects[i % projects.length]));

            // 创建设备对应的Token
            Token token = new Token();
            token.setTokenId("token-" + i);
            token.setStatus(com.xa.mass.eventbus.enums.task.TokenStatus.LOGIN_READY);
            token.setChannel(countries[i % countries.length]);

            deviceManager.addDevice(device);
            deviceManager.addToken(device.getDeviceId(), token);
        }

        System.out.println("生成了 " + 10 + " 个测试设备和Token");
    }

    private static Task createTestTask() {
        Task task = new Task();
        task.setTid("test-task-001");
        task.setTaskName("测试任务");
        task.setProject("demoApp");
        task.setTaskCountry("us");
        task.setStatus(com.xa.mass.eventbus.enums.task.TaskStatus.READY);
        task.setTaskInitNumber(100);
        task.setBatchSize(10);
        task.setRunTaskMinDeviceCnt(5);

        return task;
    }

    private static void debugDeviceEvaluation(Device device, Task task, DeviceManager deviceManager,
                                              RuleManager<Map<String, Object>> ruleManager) {
        System.out.println("\n=== 调试设备: " + device.getDeviceId() + " ===");

        // 获取设备的Token
        Token token = deviceManager.getToken(device.getDeviceId());

        // 创建设备匹配上下文
        DeviceMatchContext matchContext = new DeviceMatchContext(device, token, task, deviceManager);

        // 打印上下文信息
        System.out.println("设备信息:");
        System.out.println("  - 设备ID: " + device.getDeviceId());
        System.out.println("  - 国家: " + device.getGroupId());
        System.out.println("  - 状态: " + device.getStatus());
        System.out.println("  - Agent版本: " + device.getAgentVersion());
        System.out.println("  - 支持的应用: " + device.getSupportedApps());
        System.out.println("  - 是否可用: " + device.isAvailable());
        System.out.println("  - 是否锁定: " + device.isLocked());

        if (token != null) {
            System.out.println("Token信息:");
            System.out.println("  - TokenID: " + token.getTokenId());
            System.out.println("  - 状态: " + token.getStatus());
            System.out.println("  - 渠道: " + token.getChannel());
            System.out.println("  - 是否可分配: " + token.isAllocatable());
            System.out.println("  - 是否可用: " + token.isAvailable());
        } else {
            System.out.println("Token信息: null");
        }

        System.out.println("任务信息:");
        System.out.println("  - 任务ID: " + task.getTid());
        System.out.println("  - 项目: " + task.getProject());
        System.out.println("  - 国家: " + task.getTaskCountry());

        // 打印计算属性
        Map<String, Object> context = matchContext.getContext();
        System.out.println("计算属性:");
        System.out.println("  - appCount: " + context.get("appCount"));
        System.out.println("  - supportsProject: " + context.get("supportsProject"));
        System.out.println("  - countryMatch: " + context.get("countryMatch"));
        System.out.println("  - channelMatch: " + context.get("channelMatch"));

        // 评估每个规则
        List<RuleDefinition> rules = ruleManager.getDefaultRules();
        System.out.println("\n规则评估结果:");

        int passedRules = 0;
        for (RuleDefinition rule : rules) {
            try {
                boolean result = ruleManager.evaluate(rule, context);
                System.out.println("  - " + rule.getId() + " (" + rule.getDesc() + "): " +
                        (result ? "✓ 通过" : "✗ 失败"));
                if (result) passedRules++;

                // 打印规则内容
                System.out.println("    规则内容: " + rule.getContent());

            } catch (Exception e) {
                System.out.println("  - " + rule.getId() + ": ✗ 异常 - " + e.getMessage());
            }
        }

        System.out.println("总结: " + passedRules + "/" + rules.size() + " 个规则通过");

        if (passedRules == rules.size()) {
            System.out.println("✓ 设备匹配成功!");
        } else {
            System.out.println("✗ 设备匹配失败");
        }
    }
} 