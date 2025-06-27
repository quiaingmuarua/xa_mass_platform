package com.xa.mass.engine.example;

import com.xa.mass.engine.storage.*;
import com.xa.mass.eventbus.enums.task.TokenStatus;
import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Task;
import com.xa.mass.eventbus.model.Token;
import com.xa.mass.eventbus.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 存储工厂使用示例
 * 展示如何使用 TaskStorageFactory 创建不同类型的存储实例
 */
public class StorageFactoryExample {
    
    private static final Logger log = LoggerFactory.getLogger(StorageFactoryExample.class);
    
    public static void main(String[] args) {
        // 测试内存存储
        testInMemoryStorage();
        
        // 测试Redis存储
        testRedisStorage();
        
        // 测试存储工厂
        testStorageFactory();
    }
    
    /**
     * 测试内存存储
     */
    public static void testInMemoryStorage() {
        log.info("=== 测试内存存储 ===");
        
        // 1. 创建内存存储实例
        DeviceStorage deviceStorage = new InMemoryDeviceStorage();
        TaskStorage taskStorage = new InMemoryTaskStorage();
        RuleStorage ruleStorage = new InMemoryRuleStorage();
        
        // 2. 添加测试数据
        Device device = new Device("device-001", "1.0.0", Arrays.asList("demoApp"));
        device.setGroupId("us");
        
        Token token = new Token("token-001", "device-001", "us");
        token.setStatus(TokenStatus.LOGIN_READY);
        
        User user = new User();
        user.setName("testUser");
        
        Task task = new Task("task-001", "Test Task", "demoApp", "us", 100, "Test content", user);
        
        deviceStorage.addDevice(device);
        deviceStorage.addToken("device-001", token);
        taskStorage.saveTask(task);
        
        // 3. 验证数据
        List<Device> devices = deviceStorage.getAllDevices();
        Optional<Token> retrievedToken = deviceStorage.getToken("device-001");
        Optional<Task> retrievedTask = taskStorage.getTask("task-001");
        
        log.info("设备数量: {}", devices.size());
        log.info("Token: {}", retrievedToken.map(Token::getTokenId).orElse("null"));
        log.info("Task: {}", retrievedTask.map(Task::getTid).orElse("null"));
        
        log.info("=== 内存存储测试完成 ===");
    }
    
    /**
     * 测试Redis存储
     */
    public static void testRedisStorage() {
        log.info("=== 测试Redis存储 ===");
        
        // 注意：Redis存储需要Redis服务器运行
        // 这里只是展示如何创建Redis存储实例
        
        try {
            // 1. 创建Redis存储实例
            DeviceStorage deviceStorage = new RedisDeviceStorage();
            TaskStorage taskStorage = new RedisTaskStorage();
            RuleStorage ruleStorage = new RedisRuleStorage();
            
            log.info("Redis存储实例创建成功");
            
            // 2. 添加测试数据
            Device device = new Device("device-002", "1.0.1", Arrays.asList("demoApp"));
            device.setGroupId("gb");
            
            Token token = new Token("token-002", "device-002", "gb");
            token.setStatus(TokenStatus.LOGIN_READY);
            
            User user = new User();
            user.setName("testUser2");
            
            Task task = new Task("task-002", "Test Task 2", "demoApp", "gb", 50, "Test content 2", user);
            
            deviceStorage.addDevice(device);
            deviceStorage.addToken("device-002", token);
            taskStorage.saveTask(task);
            
            // 3. 验证数据
            List<Device> devices = deviceStorage.getAllDevices();
            Optional<Token> retrievedToken = deviceStorage.getToken("device-002");
            Optional<Task> retrievedTask = taskStorage.getTask("task-002");
            
            log.info("设备数量: {}", devices.size());
            log.info("Token: {}", retrievedToken.map(Token::getTokenId).orElse("null"));
            log.info("Task: {}", retrievedTask.map(Task::getTid).orElse("null"));
            
        } catch (Exception e) {
            log.warn("Redis存储测试失败，可能Redis服务器未运行: {}", e.getMessage());
        }
        
        log.info("=== Redis存储测试完成 ===");
    }
    
    /**
     * 测试存储工厂
     */
    public static void testStorageFactory() {
        log.info("=== 测试存储工厂 ===");
        
        // 1. 使用工厂创建内存存储
        TaskStorage inMemoryStorage = TaskStorageFactory.createStorage("memory");
        log.info("内存存储创建成功: {}", inMemoryStorage.getClass().getSimpleName());
        
        // 2. 使用工厂创建Redis存储
        try {
            TaskStorage redisStorage = TaskStorageFactory.createStorage("redis");
            log.info("Redis存储创建成功: {}", redisStorage.getClass().getSimpleName());
        } catch (Exception e) {
            log.warn("Redis存储创建失败: {}", e.getMessage());
        }
        
        // 3. 测试默认存储
        TaskStorage defaultStorage = TaskStorageFactory.createStorage("unknown");
        log.info("默认存储创建成功: {}", defaultStorage.getClass().getSimpleName());
        
        // 4. 使用存储
        User user1 = new User();
        user1.setName("factoryUser1");
        
        User user2 = new User();
        user2.setName("factoryUser2");
        
        Task task1 = new Task("task-factory-001", "Factory Task 1", "demoApp", "us", 100, "Factory content 1", user1);
        Task task2 = new Task("task-factory-002", "Factory Task 2", "demoApp", "gb", 50, "Factory content 2", user2);
        
        inMemoryStorage.saveTask(task1);
        inMemoryStorage.saveTask(task2);
        
        List<Task> allTasks = inMemoryStorage.getAllTasks();
        log.info("存储中的任务数量: {}", allTasks.size());
        
        for (Task task : allTasks) {
            log.info("任务: {} (country: {}, project: {})", 
                task.getTid(), task.getTaskCountry(), task.getProject());
        }
        
        log.info("=== 存储工厂测试完成 ===");
    }
} 