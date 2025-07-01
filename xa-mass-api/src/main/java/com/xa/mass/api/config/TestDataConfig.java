//package com.xa.mass.api.config;
//
//import com.xa.mass.engine.DeviceManager;
//import com.xa.mass.engine.TaskManager;
//import com.xa.mass.engine.rules.RuleDefinition;
//import com.xa.mass.engine.rules.RuleManager;
//import com.xa.mass.engine.rules.RuleType;
//import com.xa.mass.eventbus.enums.device.DeviceStatus;
//import com.xa.mass.eventbus.enums.task.TaskStatus;
//import com.xa.mass.eventbus.enums.task.TokenStatus;
//import com.xa.mass.eventbus.model.Device;
//import com.xa.mass.eventbus.model.Task;
//import com.xa.mass.eventbus.model.Token;
//import com.xa.mass.eventbus.model.User;
//import com.xa.mass.eventbus.enums.Project;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.CommandLineRunner;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.context.annotation.Profile;
//
//import java.util.Arrays;
//import java.util.List;
//import java.util.UUID;
//import java.util.Map;
//
///**
// * 测试数据配置
// * 仅在开发环境生成示例数据
// * 废弃
// */
//
//public class TestDataConfig {
//
//    private static final Logger logger = LoggerFactory.getLogger(TestDataConfig.class);
//
//    @Bean
//    public CommandLineRunner testDataInitializer(
//            @Autowired(required = false) TaskManager taskManager,
//            @Autowired(required = false) DeviceManager deviceManager,
//            @Autowired(required = false) RuleManager<Map<String, Object>> ruleManager) {
//
//        return args -> {
//            logger.info("开始生成测试数据...");
//
//            if (taskManager != null) {
//                generateTestTasks(taskManager);
//            }
//
//            if (deviceManager != null) {
//                generateTestDevices(deviceManager);
//            }
//
//            if (ruleManager != null) {
//                generateTestRules(ruleManager);
//            }
//
//            logger.info("测试数据生成完成");
//        };
//    }
//
//    private void generateTestTasks(TaskManager taskManager) {
//        logger.info("生成测试任务...");
//
//        // 创建测试用户
//        User user1 = new User();
//        user1.setName("testUser1");
//        user1.setPrice(100);
//
//        User user2 = new User();
//        user2.setName("testUser2");
//        user2.setPrice(200);
//
//        // 创建不同状态的任务
//        List<Task> testTasks = Arrays.asList(
//            createTask("task-001", "测试任务1", Project.DEMO_APP.getCode(), "us", 100, TaskStatus.NEW, user1),
//            createTask("task-002", "测试任务2", Project.DEMO_APP.getCode(), "gb", 50, TaskStatus.BLOCKED, user1),
//            createTask("task-003", "测试任务3", Project.DEMO_APP.getCode(), "us", 200, TaskStatus.READY, user2),
//            createTask("task-004", "测试任务4", Project.DEMO_APP.getCode(), "gb", 75, TaskStatus.RUNNING, user2),
//            createTask("task-005", "测试任务5", Project.DEMO_APP.getCode(), "us", 150, TaskStatus.PAUSED, user1),
//            createTask("task-006", "测试任务6", Project.DEMO_APP.getCode(), "gb", 80, TaskStatus.TERMINAL, user2)
//        );
//
//        for (Task task : testTasks) {
//            taskManager.createTask(createTaskRequestDto(task));
//        }
//
//        logger.info("生成了 {} 个测试任务", testTasks.size());
//    }
//
//    private void generateTestDevices(DeviceManager deviceManager) {
//        logger.info("生成测试设备...");
//
//        // 创建不同状态的设备
//        List<Device> testDevices = Arrays.asList(
//            createDevice("device-001", "us", DeviceStatus.ONLINE, Arrays.asList(Project.DEMO_APP)),
//            createDevice("device-002", "us", DeviceStatus.ONLINE, Arrays.asList(Project.DEMO_APP)),
//            createDevice("device-003", "gb", DeviceStatus.OFFLINE, Arrays.asList(Project.DEMO_APP)),
//            createDevice("device-004", "gb", DeviceStatus.ONLINE, Arrays.asList(Project.DEMO_APP)),
//            createDevice("device-005", "us", DeviceStatus.ONLINE, Arrays.asList(Project.DEMO_APP))
//        );
//
//        for (Device device : testDevices) {
//            deviceManager.addDevice(device);
//
//            // 为每个设备创建Token
//            Token token = createToken(device.getDeviceId(), device.getGroupId());
//            deviceManager.addToken(device.getDeviceId(), token);
//        }
//
//        logger.info("生成了 {} 个测试设备", testDevices.size());
//    }
//
//    private void generateTestRules(RuleManager<Map<String, Object>> ruleManager) {
//        logger.info("生成测试规则...");
//
//        // 创建测试规则
//        List<RuleDefinition> testRules = Arrays.asList(
//            createRule("rule-001", "美国设备匹配规则", "匹配美国地区的在线设备",
//                      "device.country == 'us' && device.status == 'ONLINE'", RuleType.QL_EXPRESS, 1, true),
//            createRule("rule-002", "英国设备匹配规则", "匹配英国地区的在线设备",
//                      "device.country == 'gb' && device.status == 'ONLINE'", RuleType.QL_EXPRESS, 1, true),
//            createRule("rule-003", "高优先级任务规则", "优先匹配高优先级任务",
//                      "task.priority > 100", RuleType.QL_EXPRESS, 2, true),
//            createRule("rule-004", "应用兼容性规则", "检查设备是否支持任务应用",
//                      "device.supportedApps.contains(task.project)", RuleType.QL_EXPRESS, 3, true),
//            createRule("rule-005", "设备负载规则", "检查设备是否空闲",
//                      "device.status == 'ONLINE' && !device.locked", RuleType.QL_EXPRESS, 4, true),
//            createRule("rule-006", "禁用规则示例", "这是一个被禁用的规则",
//                      "false", RuleType.QL_EXPRESS, 5, false)
//        );
//
//        for (RuleDefinition rule : testRules) {
//            ruleManager.addDefaultRule(rule);
//        }
//
//        logger.info("生成了 {} 个测试规则", testRules.size());
//    }
//
//    private RuleDefinition createRule(String id, String name, String description, String expression,
//                                     RuleType type, int priority, boolean enabled) {
//        RuleDefinition rule = new RuleDefinition();
//        rule.setId(id);
//        rule.setName(name);
//        rule.setDescription(description);
//        rule.setExpression(expression);
//        rule.setType(type);
//        rule.setPriority(priority);
//        rule.setEnabled(enabled);
//        return rule;
//    }
//
//    private Task createTask(String tid, String taskName, String project, String country,
//                           int initNumber, TaskStatus status, User user) {
//        Task task = new Task(tid, taskName, project, country, initNumber, "测试任务内容", user);
//        task.setStatus(status);
//
//        // 设置一些进度数据
//        if (status == TaskStatus.RUNNING) {
//            task.setTaskExecutedNumber(initNumber / 2);
//            task.setTaskUnExecutedNumber(initNumber / 2);
//        } else if (status == TaskStatus.TERMINAL) {
//            task.setTaskExecutedNumber(initNumber);
//            task.setTaskUnExecutedNumber(0);
//        }
//
//        return task;
//    }
//
//    private Device createDevice(String deviceId, String groupId, DeviceStatus status, List<Project> supportedProjects) {
//        Device device = new Device(deviceId, "1.0.0", supportedProjects);
//        device.setGroupId(groupId);
//        device.setStatus(status);
//        device.updateHeartbeat(); // 更新心跳时间
//        return device;
//    }
//
//    private Token createToken(String deviceId, String country) {
//        Token token = new Token(UUID.randomUUID().toString(), deviceId, country);
//        token.setStatus(TokenStatus.LOGIN_READY);
//        return token;
//    }
//
//    private com.xa.mass.engine.model.TaskCreateRequestDto createTaskRequestDto(Task task) {
//        com.xa.mass.engine.model.TaskCreateRequestDto dto = new com.xa.mass.engine.model.TaskCreateRequestDto();
//        dto.setTaskName(task.getTaskName());
//        dto.setProject(task.getProjectCode());
//        dto.setCountryCode(task.getTaskCountry());
//        dto.setTextContent(task.getTextContent());
//        dto.setUserId(task.getUser().getName());
//        return dto;
//    }
//}