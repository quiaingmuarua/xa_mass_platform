package com.xa.mass.engine.example;

import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.listener.TaskDeviceAssignListener;
import com.xa.mass.engine.listener.SimpleTaskMsgAssignListener;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.monkey.MonkeyDeviceGenerator;
import com.xa.mass.engine.monkey.MonkeyTaskGenerator;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.storage.*;
import com.xa.mass.eventbus.enums.task.TokenStatus;
import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Task;
import com.xa.mass.eventbus.model.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 存储系统使用示例
 * 展示如何使用不同的存储实现（内存、Redis等）
 */
public class StorageExample {
    
    private static final Logger log = LoggerFactory.getLogger(StorageExample.class);
    
    public static void main(String[] args) {
        // 测试基本存储功能
        testBasicStorage();
        
        // 测试任务分配功能
        testTaskAssignment();
        
        // 测试 MockTaskEngineSpringBootApp 默认配置
        testMockAppDefaultConfig();
        
        // 测试 MockTaskEngineSpringBootApp 实际运行情况
        testMockAppActualRun();
    }
    
    /**
     * 测试基本存储功能
     */
    public static void testBasicStorage() {
        log.info("=== 测试基本存储功能 ===");
        
        // 1. 创建存储实例
        DeviceStorage deviceStorage = new InMemoryDeviceStorage();
        TaskStorage taskStorage = new InMemoryTaskStorage();
        RuleStorage ruleStorage = new InMemoryRuleStorage();
        
        // 2. 创建管理器
        DeviceManager deviceManager = new DeviceManager(deviceStorage);
        TaskManager taskManager = new TaskManager(taskStorage);
        
        // 3. 添加测试数据
        Device device1 = new Device("device-001", "us", "1.0.0");
        Device device2 = new Device("device-002", "gb", "1.0.1");
        
        Token token1 = new Token("token-001", "device-001", "us", TokenStatus.LOGIN_READY);
        Token token2 = new Token("token-002", "device-002", "gb", TokenStatus.LOGIN_READY);
        
        deviceManager.addDevice(device1);
        deviceManager.addDevice(device2);
        deviceManager.addToken("device-001", token1);
        deviceManager.addToken("device-002", token2);
        
        // 4. 验证数据
        List<Device> allDevices = deviceManager.getAllDevices();
        List<Device> usDevices = deviceManager.getDevicesByCountry("us");
        List<Device> gbDevices = deviceManager.getDevicesByCountry("gb");
        
        log.info("所有设备: {}", allDevices.size());
        log.info("US设备: {}", usDevices.size());
        log.info("GB设备: {}", gbDevices.size());
        
        Token retrievedToken1 = deviceManager.getToken("device-001");
        Token retrievedToken2 = deviceManager.getToken("device-002");
        
        log.info("Token1: {}", retrievedToken1 != null ? retrievedToken1.getTokenId() : "null");
        log.info("Token2: {}", retrievedToken2 != null ? retrievedToken2.getTokenId() : "null");
        
        log.info("=== 基本存储功能测试完成 ===");
    }
    
    /**
     * 测试任务分配功能
     */
    public static void testTaskAssignment() {
        log.info("=== 测试任务分配功能 ===");
        
        // 1. 使用mock_config.json配置生成设备
        String mockConfig = "{\n" +
                "  \"devices\": [\n" +
                "    {\n" +
                "      \"deviceIdTemplate\": \"device-{i}\",\n" +
                "      \"count\": 100,\n" +
                "      \"groupId\": \"us\",\n" +
                "      \"agentVersion\": \"1.0.0\",\n" +
                "      \"supportedApps\": [\"demoApp\", \"otherApp\", \"testApp\"],\n" +
                "      \"tokens\": [\n" +
                "        {\n" +
                "          \"tokenIdTemplate\": \"token-{i}-A\",\n" +
                "          \"count\": 20,\n" +
                "          \"channel\": \"us\",\n" +
                "          \"randomStatus\": false\n" +
                "        },\n" +
                "        {\n" +
                "          \"tokenIdTemplate\": \"token-{i}-B\",\n" +
                "          \"count\": 10,\n" +
                "          \"channel\": \"us\",\n" +
                "          \"randomStatus\": false\n" +
                "        }\n" +
                "      ]\n" +
                "    },\n" +
                "    {\n" +
                "      \"deviceIdTemplate\": \"gb-device-{i}\",\n" +
                "      \"count\": 50,\n" +
                "      \"groupId\": \"gb\",\n" +
                "      \"agentVersion\": \"1.0.1\",\n" +
                "      \"supportedApps\": [\"demoApp\", \"testApp\"],\n" +
                "      \"tokens\": [\n" +
                "        {\n" +
                "          \"tokenIdTemplate\": \"gb-token-{i}-{j}\",\n" +
                "          \"count\": 1,\n" +
                "          \"channel\": \"gb\",\n" +
                "          \"randomStatus\": false\n" +
                "        }\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        
        List<Token> tokenList = new ArrayList<>();
        List<Device> devices = MonkeyDeviceGenerator.generateDevices(mockConfig, tokenList);
        
        log.info("生成了 {} 个设备和 {} 个Token", devices.size(), tokenList.size());
        
        // 2. 统计Token状态
        long loginReadyCount = tokenList.stream()
            .filter(token -> token.getStatus() == TokenStatus.LOGIN_READY)
            .count();
        long invalidCount = tokenList.stream()
            .filter(token -> token.getStatus() == TokenStatus.INVALID)
            .count();
        
        log.info("Token状态统计 - LOGIN_READY: {}, INVALID: {}", loginReadyCount, invalidCount);
        
        // 3. 统计设备分组
        long usCount = devices.stream()
            .filter(device -> "us".equals(device.getGroupId()))
            .count();
        long gbCount = devices.stream()
            .filter(device -> "gb".equals(device.getGroupId()))
            .count();
        
        log.info("设备分组统计 - US: {}, GB: {}", usCount, gbCount);
        
        // 4. 创建设备管理器并添加设备
        var deviceManager = new DeviceManager();
        for (Device device : devices) {
            deviceManager.addDevice(device);
        }
        for (Token token : tokenList) {
            deviceManager.addToken(token.getDeviceId(), token);
        }
        
        // 5. 生成测试任务
        String taskJson = MonkeyTaskGenerator.exampleTasksJsonDsl();
        log.info("任务生成JSON: {}", taskJson);
        
        com.google.gson.JsonArray taskArray = com.google.gson.JsonParser.parseString(taskJson).getAsJsonArray();
        List<TaskCreateRequestDto> taskDtos = MonkeyTaskGenerator.generateTasks(taskArray);
        log.info("生成了 {} 个任务", taskDtos.size());
        
        // 6. 测试规则匹配
        var ruleManager = RuleManagerFactory.getProjectRuleManager("demoApp");
        var recordService = new AssignmentRecordService();
        var msgAssignListener = new SimpleTaskMsgAssignListener(deviceManager, recordService);
        var deviceAssignListener = new TaskDeviceAssignListener(ruleManager, deviceManager, msgAssignListener, recordService);
        
        // 显示规则信息
        var rules = ruleManager.getAllRules();
        log.info("规则数量: {}", rules.size());
        for (var rule : rules) {
            log.info("规则: {} - {}", rule.getRuleId(), rule.getRuleContent());
        }
        
        // 7. 测试任务分配
        for (TaskCreateRequestDto dto : taskDtos) {
            log.info("测试任务: {} (country: {}, project: {})", dto.getTaskName(), dto.getCountryCode(), dto.getProject());
            
            // 获取候选设备
            List<Device> candidates = deviceManager.getDevicesByCountry(dto.getCountryCode());
            log.info("候选设备数量: {}", candidates.size());
            
            // 模拟 TaskDeviceAssignListener 的匹配逻辑
            List<Device> matchedDevices = new ArrayList<>();
            for (Device device : candidates) {
                Token token = deviceManager.getToken(device.getDeviceId());
                if (token != null) {
                    // 检查Token状态
                    boolean tokenAllocatable = token.isAllocatable();
                    boolean tokenAvailable = token.isAvailable();
                    
                    if (tokenAllocatable && tokenAvailable) {
                        // 尝试锁定设备
                        if (deviceManager.tryLockDevice(device.getDeviceId())) {
                            matchedDevices.add(device);
                            log.info("✅ 设备匹配成功: {} (token: {}, status: {})", 
                                device.getDeviceId(), token.getTokenId(), token.getStatus());
                        } else {
                            log.info("❌ 设备锁定失败: {} (可能已被其他任务占用)", device.getDeviceId());
                        }
                    } else {
                        log.debug("❌ 设备不匹配: {} (token: {}, allocatable: {}, available: {})", 
                            device.getDeviceId(), token.getTokenId(), tokenAllocatable, tokenAvailable);
                    }
                } else {
                    log.debug("❌ 设备无Token: {}", device.getDeviceId());
                }
            }
            
            log.info("任务 {} 匹配设备数量: {}", dto.getTaskName(), matchedDevices.size());
            
            // 解锁设备，为下一个任务做准备
            for (Device device : matchedDevices) {
                deviceManager.unlockDevice(device.getDeviceId());
            }
        }
        
        log.info("=== 任务分配功能测试完成 ===");
    }
    
    /**
     * 测试 MockTaskEngineSpringBootApp 的默认配置
     */
    public static void testMockAppDefaultConfig() {
        log.info("=== 测试 MockTaskEngineSpringBootApp 默认配置 ===");
        
        // 1. 使用默认配置生成设备
        String defaultConfig = MonkeyDeviceGenerator.exampleJsonDsl();
        log.info("默认配置: {}", defaultConfig);
        
        List<Token> tokenList = new ArrayList<>();
        List<Device> devices = MonkeyDeviceGenerator.generateDevices(defaultConfig, tokenList);
        
        log.info("生成了 {} 个设备和 {} 个Token", devices.size(), tokenList.size());
        
        // 2. 统计Token状态
        long loginReadyCount = tokenList.stream()
            .filter(token -> token.getStatus() == TokenStatus.LOGIN_READY)
            .count();
        long invalidCount = tokenList.stream()
            .filter(token -> token.getStatus() == TokenStatus.INVALID)
            .count();
        
        log.info("Token状态统计 - LOGIN_READY: {}, INVALID: {}", loginReadyCount, invalidCount);
        
        // 3. 统计设备分组
        long usCount = devices.stream()
            .filter(device -> "us".equals(device.getGroupId()))
            .count();
        long gbCount = devices.stream()
            .filter(device -> "gb".equals(device.getGroupId()))
            .count();
        
        log.info("设备分组统计 - US: {}, GB: {}", usCount, gbCount);
        
        // 4. 创建设备管理器并添加设备
        var deviceManager = new DeviceManager();
        for (Device device : devices) {
            deviceManager.addDevice(device);
        }
        for (Token token : tokenList) {
            deviceManager.addToken(token.getDeviceId(), token);
        }
        
        // 5. 生成测试任务
        String taskJson = MonkeyTaskGenerator.exampleTasksJsonDsl();
        com.google.gson.JsonArray taskArray = com.google.gson.JsonParser.parseString(taskJson).getAsJsonArray();
        List<TaskCreateRequestDto> taskDtos = MonkeyTaskGenerator.generateTasks(taskArray);
        
        log.info("生成了 {} 个任务", taskDtos.size());
        
        // 6. 测试任务分配
        for (TaskCreateRequestDto dto : taskDtos) {
            log.info("测试任务: {} (country: {}, project: {})", dto.getTaskName(), dto.getCountryCode(), dto.getProject());
            
            // 获取候选设备
            List<Device> candidates = deviceManager.getDevicesByCountry(dto.getCountryCode());
            log.info("候选设备数量: {}", candidates.size());
            
            // 模拟 TaskDeviceAssignListener 的匹配逻辑
            List<Device> matchedDevices = new ArrayList<>();
            for (Device device : candidates) {
                Token token = deviceManager.getToken(device.getDeviceId());
                if (token != null) {
                    // 检查Token状态
                    boolean tokenAllocatable = token.isAllocatable();
                    boolean tokenAvailable = token.isAvailable();
                    
                    if (tokenAllocatable && tokenAvailable) {
                        // 尝试锁定设备
                        if (deviceManager.tryLockDevice(device.getDeviceId())) {
                            matchedDevices.add(device);
                            log.info("✅ 设备匹配成功: {} (token: {}, status: {})", 
                                device.getDeviceId(), token.getTokenId(), token.getStatus());
                        } else {
                            log.info("❌ 设备锁定失败: {} (可能已被其他任务占用)", device.getDeviceId());
                        }
                    } else {
                        log.debug("❌ 设备不匹配: {} (token: {}, allocatable: {}, available: {})", 
                            device.getDeviceId(), token.getTokenId(), tokenAllocatable, tokenAvailable);
                    }
                } else {
                    log.debug("❌ 设备无Token: {}", device.getDeviceId());
                }
            }
            
            log.info("任务 {} 匹配设备数量: {}", dto.getTaskName(), matchedDevices.size());
            
            // 解锁设备，为下一个任务做准备
            for (Device device : matchedDevices) {
                deviceManager.unlockDevice(device.getDeviceId());
            }
        }
        
        log.info("=== 测试完成 ===");
    }
    
    /**
     * 测试 MockTaskEngineSpringBootApp 的实际运行情况
     */
    public static void testMockAppActualRun() {
        log.info("=== 测试 MockTaskEngineSpringBootApp 实际运行情况 ===");
        
        // 1. 模拟 MassEngine 的启动过程
        var deviceManager = new DeviceManager();
        var ruleManager = RuleManagerFactory.getProjectRuleManager("demoApp");
        var recordService = new AssignmentRecordService();
        var msgAssignListener = new SimpleTaskMsgAssignListener(deviceManager, recordService);
        var deviceAssignListener = new TaskDeviceAssignListener(ruleManager, deviceManager, msgAssignListener, recordService);
        
        // 2. 使用默认配置生成设备和Token
        String defaultConfig = MonkeyDeviceGenerator.exampleJsonDsl();
        List<Token> tokenList = new ArrayList<>();
        List<Device> devices = MonkeyDeviceGenerator.generateDevices(defaultConfig, tokenList);
        
        log.info("生成了 {} 个设备和 {} 个Token", devices.size(), tokenList.size());
        
        // 3. 添加设备和Token到管理器
        for (Device device : devices) {
            deviceManager.addDevice(device);
        }
        for (Token token : tokenList) {
            deviceManager.addToken(token.getDeviceId(), token);
        }
        
        // 4. 验证设备和Token添加情况
        List<Device> allDevices = deviceManager.getAllDevices();
        List<Device> usDevices = deviceManager.getDevicesByCountry("us");
        List<Device> gbDevices = deviceManager.getDevicesByCountry("gb");
        
        log.info("设备验证 - 总数: {}, US: {}, GB: {}", allDevices.size(), usDevices.size(), gbDevices.size());
        
        // 5. 显示前几个设备的详细信息
        for (int i = 0; i < Math.min(3, allDevices.size()); i++) {
            Device device = allDevices.get(i);
            Token token = deviceManager.getToken(device.getDeviceId());
            log.info("设备 {}: id={}, groupId={}, status={}, token={}, tokenStatus={}", 
                i + 1, device.getDeviceId(), device.getGroupId(), device.getStatus(),
                token != null ? token.getTokenId() : "null",
                token != null ? token.getStatus() : "null");
        }
        
        // 6. 生成测试任务
        String taskJson = MonkeyTaskGenerator.exampleTasksJsonDsl();
        com.google.gson.JsonArray taskArray = com.google.gson.JsonParser.parseString(taskJson).getAsJsonArray();
        List<TaskCreateRequestDto> taskDtos = MonkeyTaskGenerator.generateTasks(taskArray);
        
        log.info("生成了 {} 个任务", taskDtos.size());
        
        // 7. 测试任务分配，模拟实际运行过程
        for (TaskCreateRequestDto dto : taskDtos) {
            log.info("测试任务: {} (country: {}, project: {})", dto.getTaskName(), dto.getCountryCode(), dto.getProject());
            
            // 获取候选设备
            List<Device> candidates = deviceManager.getDevicesByCountry(dto.getCountryCode());
            log.info("候选设备数量: {}", candidates.size());
            
            // 模拟 TaskDeviceAssignListener 的匹配逻辑
            List<Device> matchedDevices = new ArrayList<>();
            for (Device device : candidates) {
                Token token = deviceManager.getToken(device.getDeviceId());
                if (token != null) {
                    // 检查Token状态
                    boolean tokenAllocatable = token.isAllocatable();
                    boolean tokenAvailable = token.isAvailable();
                    
                    log.debug("设备 {}: token={}, status={}, allocatable={}, available={}", 
                        device.getDeviceId(), token.getTokenId(), token.getStatus(), tokenAllocatable, tokenAvailable);
                    
                    if (tokenAllocatable && tokenAvailable) {
                        // 尝试锁定设备
                        if (deviceManager.tryLockDevice(device.getDeviceId())) {
                            matchedDevices.add(device);
                            log.info("✅ 设备匹配成功: {} (token: {}, status: {})", 
                                device.getDeviceId(), token.getTokenId(), token.getStatus());
                        } else {
                            log.info("❌ 设备锁定失败: {} (可能已被其他任务占用)", device.getDeviceId());
                        }
                    } else {
                        log.debug("❌ 设备不匹配: {} (token: {}, allocatable: {}, available: {})", 
                            device.getDeviceId(), token.getTokenId(), tokenAllocatable, tokenAvailable);
                    }
                } else {
                    log.debug("❌ 设备无Token: {}", device.getDeviceId());
                }
            }
            
            log.info("任务 {} 匹配设备数量: {}", dto.getTaskName(), matchedDevices.size());
            
            // 解锁设备，为下一个任务做准备
            for (Device device : matchedDevices) {
                deviceManager.unlockDevice(device.getDeviceId());
            }
        }
        
        log.info("=== 测试完成 ===");
    }
} 