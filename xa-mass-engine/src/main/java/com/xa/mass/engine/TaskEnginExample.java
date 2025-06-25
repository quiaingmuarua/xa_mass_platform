package com.xa.mass.engine;

import com.xa.mass.engine.model.Device;
import com.xa.mass.engine.model.DeviceMatchContext;
import com.xa.mass.engine.model.Token;
import com.xa.mass.engine.model.enums.DeviceStatus;
import com.xa.mass.engine.model.enums.TaskStatus;
import com.xa.mass.engine.model.enums.TokenStatus;
import com.xa.mass.engine.model.task.Task;
import com.xa.mass.engine.model.TaskMsg;
import com.xa.mass.engine.model.task.TaskCreateRequestDto;
import com.xa.mass.engine.rules.RuleConfig;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleType;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class TaskEnginExample {

    /**
     * 初始化规则管理器，配置设备匹配规则
     */
    public static RuleManager<Map<String, Object>> initRuleManager() throws Exception {
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>();

        // 使用规则配置类加载默认规则
        List<RuleDefinition> defaultRules = RuleConfig.getDefaultDeviceMatchRules();
        ruleManager.addDefaultRules(defaultRules);

        System.out.println("Loaded " + defaultRules.size() + " default rules:");
        for (RuleDefinition rule : defaultRules) {
            System.out.println("  - " + rule.getId() + ": " + rule.getDesc());
        }

        return ruleManager;
    }

    /**
     * 初始化规则管理器，使用项目特定规则
     */
    public static RuleManager<Map<String, Object>> initRuleManagerWithProjectRules(String projectName) throws Exception {
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>();

        // 使用项目特定规则
        List<RuleDefinition> projectRules = RuleConfig.getProjectSpecificRules(projectName);
        ruleManager.addDefaultRules(projectRules);

        System.out.println("Loaded " + projectRules.size() + " project-specific rules for " + projectName + ":");
        for (RuleDefinition rule : projectRules) {
            System.out.println("  - " + rule.getId() + ": " + rule.getDesc());
        }

        return ruleManager;
    }

    /**
     * 初始化规则管理器，使用宽松规则（用于测试）
     */
    public static RuleManager<Map<String, Object>> initRuleManagerWithLooseRules() throws Exception {
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>();

        // 使用宽松规则
        List<RuleDefinition> looseRules = RuleConfig.getLooseDeviceMatchRules();
        ruleManager.addDefaultRules(looseRules);

        System.out.println("Loaded " + looseRules.size() + " loose rules for testing:");
        for (RuleDefinition rule : looseRules) {
            System.out.println("  - " + rule.getId() + ": " + rule.getDesc());
        }

        return ruleManager;
    }

    public static TaskManager initTaskManger() {
        // 1. 初始化调度器和任务管理器
        SimpleTaskScheduler scheduler = new SimpleTaskScheduler();
        return new TaskManager(scheduler);
    }

    /**
     * 使用规则引擎匹配设备
     */
    public static List<Device> matchDevicesWithRules(RuleManager<Map<String, Object>> ruleManager,
                                                     DeviceManager deviceManager,
                                                     Task task,
                                                     int maxDeviceCount) {
        List<Device> matchedDevices = new ArrayList<>();
        List<Device> candidates = deviceManager.getDevicesByCountry(task.getTaskCountry());

        System.out.println("Matching devices for task " + task.getTid() + " (country: " + task.getTaskCountry() + ")");
        System.out.println("Candidate devices: " + candidates.size());

        for (Device device : candidates) {
            if (matchedDevices.size() >= maxDeviceCount) break;

            // 获取设备的Token
            Token token = deviceManager.getToken(device.getDeviceId());

            // 创建设备匹配上下文
            DeviceMatchContext matchContext = new DeviceMatchContext(device, token, task);

            try {
                // 使用规则引擎评估设备是否匹配
                List<String> hitRules = ruleManager.evaluateDefaultRules(matchContext.getContext());

                // 如果所有规则都通过，则匹配成功
                if (hitRules.size() == ruleManager.getDefaultRules().size()) {
                    if (deviceManager.tryLockDevice(device.getDeviceId())) {
                        System.out.println("✓ Rule matched: " + matchContext + " (hit rules: " + hitRules + ")");
                        matchedDevices.add(device);
                    } else {
                        System.out.println("✗ Device locked: " + device.getDeviceId());
                    }
                } else {
                    System.out.println("✗ Rule not matched: " + matchContext + " (hit rules: " + hitRules + "/" + ruleManager.getDefaultRules().size() + ")");
                }
            } catch (Exception e) {
                System.err.println("Error evaluating rules for device " + device.getDeviceId() + ": " + e.getMessage());
            }
        }

        System.out.println("Total matched devices: " + matchedDevices.size());
        return matchedDevices;
    }

    public static void main(String[] args) throws Exception {
        TaskManager taskManager = initTaskManger();

        // 可以选择不同的规则配置
        RuleManager<Map<String, Object>> ruleManager;
        if (args.length > 0 && "loose".equals(args[0])) {
            ruleManager = initRuleManagerWithLooseRules();
        } else if (args.length > 0 && "project".equals(args[0])) {
            ruleManager = initRuleManagerWithProjectRules("demoApp");
        } else {
            ruleManager = initRuleManager();
        }
        
        String[] countries = {"gb", "us"};
        int msgPerTask = 20;
        int batchSize = 8;
        String projectName = "demoApp";

        List<Task> allTasks = new ArrayList<>();
        for (String country : countries) {
            TaskCreateRequestDto dto = getTaskCreateRequestDto(country, projectName, msgPerTask, batchSize);
            Task task = taskManager.createTask(dto);
            task.setRunTaskMinDeviceCnt(30);
            task.setBatchSize(batchSize);
            System.out.println("new_task " + task);
            allTasks.add(task);
        }
        System.out.println("Created tasks: " + allTasks.size());

        // 2. 批量创建设备和token
        DeviceManager deviceManager = new DeviceManager();
        int deviceCount = 100; // 3k
        String[] deviceCountries = {"us", "gb", "fr"};
        for (int i = 0; i < deviceCount; i++) {
            Device device = new Device();
            device.setDeviceId("device-" + i);
            device.setStatus(DeviceStatus.ONLINE);
            device.setGroupId(deviceCountries[i % deviceCountries.length]);
            device.setAgentVersion("1.0.0");

            // 设置支持的应用列表
            List<String> supportedApps = new ArrayList<>();
            supportedApps.add("demoApp");
            if (i % 3 == 0) supportedApps.add("otherApp");
            if (i % 5 == 0) supportedApps.add("testApp");
            device.setSupportedApps(supportedApps);
            
            deviceManager.addDevice(device);

            // 只绑定一个token
            Token token = new Token();
            token.setTokenId("token-" + i);
            token.setDeviceId(device.getDeviceId());
            token.setChannel(device.getGroupId());
            token.setStatus(ThreadLocalRandom.current().nextBoolean() ? TokenStatus.LOGIN_READY : TokenStatus.INVALID);
            deviceManager.addToken(device.getDeviceId(), token);
            System.out.println("new_device " + device);
        }
        System.out.println("Created devices: " + deviceCount);

        // 3. 审核任务（设为READY）
        for (Task task : allTasks) {
            task.transitionTo(TaskStatus.READY);
        }
        System.out.println("Approved tasks: " + allTasks.stream().filter(t -> t.getStatus() == TaskStatus.READY).count());

        // 4. 使用规则引擎进行任务绑定设备
        Map<String, List<Device>> taskDeviceMap = new HashMap<>();
        for (Task task : allTasks) {
            if (task.getStatus() != TaskStatus.READY) continue;

            int maxDeviceCount = (int) Math.ceil((double) task.getTaskInitNumber() / task.getBatchSize());
            List<Device> matched = matchDevicesWithRules(ruleManager, deviceManager, task,
                    Math.min(task.getRunTaskMinDeviceCnt(), maxDeviceCount));
            
            if (matched.size() >= task.getRunTaskMinDeviceCnt() && matched.size() <= maxDeviceCount) {
                task.setScheduleDeviceCnt(matched.size());
                task.transitionTo(TaskStatus.RUNNING);
                taskDeviceMap.put(task.getTid(), matched);
                System.out.println("✓ Task " + task.getTid() + " matched " + matched.size() + " devices");
            } else {
                // 匹配失败，释放锁
                for (Device d : matched) deviceManager.unlockDevice(d.getDeviceId());
                System.out.println("✗ Task " + task.getTid() + " failed to match enough devices: " + matched.size() + "/" + task.getRunTaskMinDeviceCnt());
            }
        }
        System.out.println("Tasks scheduled: " + taskDeviceMap.size());

        // 5. 消息批次与推送队列
        List<TaskMsg> pushQueue = new ArrayList<>();
        for (Map.Entry<String, List<Device>> entry : taskDeviceMap.entrySet()) {
            String taskId = entry.getKey();
            List<Device> devices = entry.getValue();
            Task task = allTasks.stream().filter(t -> t.getTid().equals(taskId)).findFirst().orElse(null);
            if (task == null) continue;
            int batchSizeForTask = task.getBatchSize();
            int batchId = 0;
            for (Device device : devices) {
                for (int i = 0; i < batchSizeForTask; i++) {
                    String msgId = UUID.randomUUID().toString();
                    // 绑定token到消息
                    Token token = deviceManager.getToken(device.getDeviceId());
                    String tokenId = token != null ? token.getTokenId() : null;
                    TaskMsg msg = new TaskMsg(msgId, taskId, device.getDeviceId(), tokenId, "batch-" + batchId);
                    System.out.println(msg);
                    pushQueue.add(msg);
                }
                batchId++;
            }
        }
        System.out.println("Push queue size: " + pushQueue.size());
        // 可在此处模拟推送到 gateway inputQueue
    }

    private static TaskCreateRequestDto getTaskCreateRequestDto(String country, String projectName, int msgPerTask, int batchSize) {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName("Task-" + country);
        dto.setProject(projectName);
        dto.setCountryCode(country);
        dto.setUserId("user-" + country);
        dto.setTextContent("content for " + country);
        // 这里设置targetList模拟2k条msg
        List<String> targetList = new ArrayList<>();
        for (int i = 0; i < msgPerTask; i++) {
            targetList.add("number-" + country + "-" + i);
        }
        dto.setTargetList(targetList);
        dto.setBatchSize(batchSize);
        return dto;
    }
}
