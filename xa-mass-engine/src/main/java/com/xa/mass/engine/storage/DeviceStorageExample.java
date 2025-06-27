package com.xa.mass.engine.storage;

import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.listener.TaskDeviceAssignListener;
import com.xa.mass.engine.listener.SimpleTaskMsgAssignListener;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.monkey.MonkeyDeviceGenerator;
import com.xa.mass.engine.monkey.MonkeyTaskGenerator;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.eventbus.enums.device.DeviceStatus;
import com.xa.mass.eventbus.enums.task.TokenStatus;
import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Task;
import com.xa.mass.eventbus.model.Token;
import com.xa.mass.eventbus.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备存储使用示例
 * 展示如何使用不同的存储实现
 */
public class DeviceStorageExample {
    
    private static final Logger log = LoggerFactory.getLogger(DeviceStorageExample.class);
    
    public static void main(String[] args) {
        log.info("=== 设备存储测试开始 ===");
        
        // 1. 创建设备管理器
        DeviceManager deviceManager = new DeviceManager();
        
        // 2. 生成测试设备和Token
        List<Token> tokenList = new ArrayList<>();
        String deviceJson = MonkeyDeviceGenerator.exampleJsonDsl();
        log.info("设备生成JSON: {}", deviceJson);
        
        List<Device> devices = MonkeyDeviceGenerator.generateDevices(deviceJson, tokenList);
        log.info("生成了 {} 个设备和 {} 个Token", devices.size(), tokenList.size());
        
        // 3. 添加设备和Token
        for (Device device : devices) {
            deviceManager.addDevice(device);
            log.info("添加设备: {} (groupId: {}, status: {})", 
                device.getDeviceId(), device.getGroupId(), device.getStatus());
        }
        
        for (Token token : tokenList) {
            deviceManager.addToken(token.getDeviceId(), token);
            log.info("添加Token: {} (deviceId: {}, status: {}, channel: {})", 
                token.getTokenId(), token.getDeviceId(), token.getStatus(), token.getChannel());
        }
        
        // 4. 验证设备存储
        List<Device> allDevices = deviceManager.getAllDevices();
        List<Device> usDevices = deviceManager.getDevicesByCountry("us");
        List<Device> gbDevices = deviceManager.getDevicesByCountry("gb");
        
        log.info("设备验证 - 总数: {}, US: {}, GB: {}", allDevices.size(), usDevices.size(), gbDevices.size());
        
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
        var rules = ruleManager.getDefaultRules();
        log.info("使用 {} 个规则进行评估", rules.size());
        for (var rule : rules) {
            log.info("规则: {} - {}", rule.getId(), rule.getContent());
        }
        
        // 7. 测试设备匹配
        for (TaskCreateRequestDto dto : taskDtos) {
            log.info("测试任务: {} (country: {}, project: {})", 
                dto.getTaskName(), dto.getCountryCode(), dto.getProject());
            
            // 获取候选设备
            List<Device> candidates = deviceManager.getDevicesByCountry(dto.getCountryCode());
            log.info("候选设备数量: {}", candidates.size());
            
            // 测试前几个设备
            for (int i = 0; i < Math.min(3, candidates.size()); i++) {
                Device device = candidates.get(i);
                Token token = deviceManager.getToken(device.getDeviceId());
                
                log.info("测试设备 {}: {} (groupId: {}, status: {})", 
                    i + 1, device.getDeviceId(), device.getGroupId(), device.getStatus());
                if (token != null) {
                    log.info("  Token: {} (status: {}, channel: {})", 
                        token.getTokenId(), token.getStatus(), token.getChannel());
                }
            }
        }
        
        // 8. 测试任务分配
        log.info("=== 开始测试任务分配 ===");
        for (TaskCreateRequestDto dto : taskDtos) {
            // 创建任务对象
            Task task = new Task();
            task.setTid(dto.getTaskName());
            task.setTaskName(dto.getTaskName());
            task.setProject(dto.getProject());
            task.setTaskCountry(dto.getCountryCode());
            task.setTaskInitNumber(dto.getTargetList().size());
            task.setBatchSize(dto.getBatchSize());
            task.setRunTaskMinDeviceCnt(dto.getBatchSize());
            
            log.info("测试分配任务: {} (country: {}, project: {}, initNumber: {})", 
                task.getTid(), task.getTaskCountry(), task.getProject(), task.getTaskInitNumber());
            
            // 执行设备分配
            deviceAssignListener.onTaskAssign(task);
        }
        
        log.info("=== 设备存储测试完成 ===");
        
        // 测试 MockTaskEngineSpringBootApp 默认配置
        testMockAppDefaultConfig();
        
        // 测试 MockTaskEngineSpringBootApp 实际运行情况
        testMockAppActualRun();
    }
    
    private static void demonstrateDeviceManager(DeviceManager manager) {
        try {
            // 创建示例设备
            Device device = new Device();
            device.setDeviceId("device_001");
            device.setGroupId("US");
            device.setStatus(DeviceStatus.ONLINE);
            device.setAgentVersion("1.0.0");
            device.setSupportedApps(Arrays.asList("app1", "app2"));
            
            // 创建示例Token
            Token token = new Token();
            token.setTokenId("token_001");
            token.setDeviceId("device_001");
            token.setStatus(TokenStatus.LOGIN_READY);
            token.setChannel("US");
            
            // 演示设备操作
            log.info("存储类型: {}", manager.getClass().getSimpleName());
            
            // 添加设备
            manager.addDevice(device);
            log.info("设备添加成功");
            
            // 获取设备
            Device retrievedDevice = manager.getDevice("device_001");
            log.info("设备获取成功: {}", (retrievedDevice != null));
            
            // 添加Token
            manager.addToken("device_001", token);
            log.info("Token添加成功");
            
            // 获取Token
            Token retrievedToken = manager.getToken("device_001");
            log.info("Token获取成功: {}", (retrievedToken != null));
            
            // 测试设备锁定
            boolean locked = manager.tryLockDevice("device_001");
            log.info("设备锁定成功: {}", locked);
            
            boolean isLocked = manager.isLocked("device_001");
            log.info("设备锁定状态: {}", isLocked);
            
            // 解锁设备
            manager.unlockDevice("device_001");
            log.info("设备解锁成功");
            
            // 按国家获取设备
            var devicesByCountry = manager.getDevicesByCountry("US");
            log.info("按国家获取设备数量: {}", devicesByCountry.size());
            
            log.info("设备存储操作演示完成");
            
        } catch (Exception e) {
            log.error("设备存储操作失败: {}", e.getMessage());
        }
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
        
        // 3. 验证设备分组
        long usDevices = devices.stream()
            .filter(device -> "us".equals(device.getGroupId()))
            .count();
        long gbDevices = devices.stream()
            .filter(device -> "gb".equals(device.getGroupId()))
            .count();
        
        log.info("设备分组统计 - US: {}, GB: {}", usDevices, gbDevices);
        
        // 4. 测试规则匹配
        var deviceManager = new DeviceManager();
        var ruleManager = RuleManagerFactory.getProjectRuleManager("demoApp");
        var recordService = new AssignmentRecordService();
        var msgAssignListener = new SimpleTaskMsgAssignListener(deviceManager, recordService);
        var deviceAssignListener = new TaskDeviceAssignListener(ruleManager, deviceManager, msgAssignListener, recordService);
        
        // 添加设备到管理器
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
            
            // 测试规则匹配
            int matchedCount = 0;
            for (Device device : candidates) {
                Token token = deviceManager.getToken(device.getDeviceId());
                if (token != null && token.isAllocatable() && token.isAvailable()) {
                    matchedCount++;
                    log.debug("✅ 设备匹配: {} (token: {}, status: {})", 
                        device.getDeviceId(), token.getTokenId(), token.getStatus());
                } else {
                    log.debug("❌ 设备不匹配: {} (token: {}, allocatable: {}, available: {})", 
                        device.getDeviceId(), 
                        token != null ? token.getTokenId() : "null",
                        token != null ? token.isAllocatable() : "null",
                        token != null ? token.isAvailable() : "null");
                }
            }
            
            log.info("任务 {} 匹配设备数量: {}", dto.getTaskName(), matchedCount);
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