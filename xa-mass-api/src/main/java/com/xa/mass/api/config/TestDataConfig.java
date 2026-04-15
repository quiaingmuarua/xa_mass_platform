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
// * 娴嬭瘯鏁版嵁閰嶇疆
// * 浠呭湪寮€鍙戠幆澧冪敓鎴愮ず渚嬫暟鎹?
// * 搴熷純
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
//            logger.info("寮€濮嬬敓鎴愭祴璇曟暟鎹?..");
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
//            logger.info("娴嬭瘯鏁版嵁鐢熸垚瀹屾垚");
//        };
//    }
//
//    private void generateTestTasks(TaskManager taskManager) {
//        logger.info("鐢熸垚娴嬭瘯浠诲姟...");
//
//        // 鍒涘缓娴嬭瘯鐢ㄦ埛
//        User user1 = new User();
//        user1.setName("testUser1");
//        user1.setPrice(100);
//
//        User user2 = new User();
//        user2.setName("testUser2");
//        user2.setPrice(200);
//
//        // 鍒涘缓涓嶅悓鐘舵€佺殑浠诲姟
//        List<Task> testTasks = Arrays.asList(
//            createTask("task-001", "娴嬭瘯浠诲姟1", Project.DEMO_APP.getCode(), "us", 100, TaskStatus.NEW, user1),
//            createTask("task-002", "娴嬭瘯浠诲姟2", Project.DEMO_APP.getCode(), "gb", 50, TaskStatus.BLOCKED, user1),
//            createTask("task-003", "娴嬭瘯浠诲姟3", Project.DEMO_APP.getCode(), "us", 200, TaskStatus.READY, user2),
//            createTask("task-004", "娴嬭瘯浠诲姟4", Project.DEMO_APP.getCode(), "gb", 75, TaskStatus.RUNNING, user2),
//            createTask("task-005", "娴嬭瘯浠诲姟5", Project.DEMO_APP.getCode(), "us", 150, TaskStatus.PAUSED, user1),
//            createTask("task-006", "娴嬭瘯浠诲姟6", Project.DEMO_APP.getCode(), "gb", 80, TaskStatus.TERMINAL, user2)
//        );
//
//        for (Task task : testTasks) {
//            taskManager.createTask(createTaskRequestDto(task));
//        }
//
//        logger.info("鐢熸垚浜?{} 涓祴璇曚换鍔?, testTasks.size());
//    }
//
//    private void generateTestDevices(DeviceManager deviceManager) {
//        logger.info("鐢熸垚娴嬭瘯璁惧...");
//
//        // 鍒涘缓涓嶅悓鐘舵€佺殑璁惧
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
//            // 涓烘瘡涓澶囧垱寤篢oken
//            Token token = createToken(device.getDeviceId(), device.getDeviceGroupId());
//            deviceManager.addToken(device.getDeviceId(), token);
//        }
//
//        logger.info("鐢熸垚浜?{} 涓祴璇曡澶?, testDevices.size());
//    }
//
//    private void generateTestRules(RuleManager<Map<String, Object>> ruleManager) {
//        logger.info("鐢熸垚娴嬭瘯瑙勫垯...");
//
//        // 鍒涘缓娴嬭瘯瑙勫垯
//        List<RuleDefinition> testRules = Arrays.asList(
//            createRule("rule-001", "缇庡浗璁惧鍖归厤瑙勫垯", "鍖归厤缇庡浗鍦板尯鐨勫湪绾胯澶?,
//                      "device.country == 'us' && device.status == 'ONLINE'", RuleType.QL_EXPRESS, 1, true),
//            createRule("rule-002", "鑻卞浗璁惧鍖归厤瑙勫垯", "鍖归厤鑻卞浗鍦板尯鐨勫湪绾胯澶?,
//                      "device.country == 'gb' && device.status == 'ONLINE'", RuleType.QL_EXPRESS, 1, true),
//            createRule("rule-003", "楂樹紭鍏堢骇浠诲姟瑙勫垯", "浼樺厛鍖归厤楂樹紭鍏堢骇浠诲姟",
//                      "task.priority > 100", RuleType.QL_EXPRESS, 2, true),
//            createRule("rule-004", "搴旂敤鍏煎鎬ц鍒?, "妫€鏌ヨ澶囨槸鍚︽敮鎸佷换鍔″簲鐢?,
//                      "device.supportedApps.contains(task.project)", RuleType.QL_EXPRESS, 3, true),
//            createRule("rule-005", "璁惧璐熻浇瑙勫垯", "妫€鏌ヨ澶囨槸鍚︾┖闂?,
//                      "device.status == 'ONLINE' && !device.locked", RuleType.QL_EXPRESS, 4, true),
//            createRule("rule-006", "绂佺敤瑙勫垯绀轰緥", "杩欐槸涓€涓绂佺敤鐨勮鍒?,
//                      "false", RuleType.QL_EXPRESS, 5, false)
//        );
//
//        for (RuleDefinition rule : testRules) {
//            ruleManager.addDefaultRule(rule);
//        }
//
//        logger.info("鐢熸垚浜?{} 涓祴璇曡鍒?, testRules.size());
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
//        Task task = new Task(tid, taskName, project, country, initNumber, "娴嬭瘯浠诲姟鍐呭", user);
//        task.setStatus(status);
//
//        // 璁剧疆涓€浜涜繘搴︽暟鎹?
//        if (status == TaskStatus.RUNNING) {
//            task.setTaskSuccessNumber(initNumber / 2);
//            task.setTaskNonSuccessNumber(initNumber / 2);
//        } else if (status == TaskStatus.TERMINAL) {
//            task.setTaskSuccessNumber(initNumber);
//            task.setTaskNonSuccessNumber(0);
//        }
//
//        return task;
//    }
//
//    private Device createDevice(String deviceId, String deviceGroupId, DeviceStatus status, List<Project> supportedProjects) {
//        Device device = new Device(deviceId, "1.0.0", supportedProjects);
//        device.setDeviceGroupId(deviceGroupId);
//        device.setStatus(status);
//        device.updateHeartbeat(); // 鏇存柊蹇冭烦鏃堕棿
//        return device;
//    }
//
//    private Token createToken(String deviceId, String country) {
//        Token token = new Token(UUID.randomUUID().toString(), deviceId, country);
//        token.setStatus(TokenStatus.IDLE);
//        return token;
//    }
//
//}
