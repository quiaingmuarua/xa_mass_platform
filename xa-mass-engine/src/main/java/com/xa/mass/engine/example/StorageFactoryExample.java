package com.xa.mass.engine.example;

import com.xa.mass.base.enums.task.TokenStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Token;
import com.xa.mass.base.model.User;
import com.xa.mass.engine.storage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 瀛樺偍宸ュ巶浣跨敤绀轰緥
 * 灞曠ず濡備綍浣跨敤 TaskStorageFactory 鍒涘缓涓嶅悓绫诲瀷鐨勫瓨鍌ㄥ疄渚?
 */
public class StorageFactoryExample {

    private static final Logger log = LoggerFactory.getLogger(StorageFactoryExample.class);

    public static void main(String[] args) {
        // 娴嬭瘯鍐呭瓨瀛樺偍
        testInMemoryStorage();

        // 娴嬭瘯Redis瀛樺偍
        testRedisStorage();

        // 娴嬭瘯瀛樺偍宸ュ巶
        testStorageFactory();
    }

    /**
     * 娴嬭瘯鍐呭瓨瀛樺偍
     */
    public static void testInMemoryStorage() {
        log.info("=== 娴嬭瘯鍐呭瓨瀛樺偍 ===");

        // 1. 鍒涘缓鍐呭瓨瀛樺偍瀹炰緥
        DeviceStorage deviceStorage = new InMemoryDeviceStorage();
        TaskStorage taskStorage = new InMemoryTaskStorage();
        RuleStorage ruleStorage = new InMemoryRuleStorage();

        // 2. 娣诲姞娴嬭瘯鏁版嵁
        Device device = new Device("device-001", "1.0.0", Arrays.asList("demoApp"));
        device.setDeviceGroupId("us");

        Token token = new Token("token-001", "device-001", "us");
        token.setStatus(TokenStatus.IDLE);

        User user = new User();
        user.setName("testUser");

        Task task = new Task("task-001", "Test Task", "demoApp", "us", 100, java.util.Map.of("textContent", "Test content"), user);

        deviceStorage.addDevice(device);
        deviceStorage.addToken("device-001", token);
        taskStorage.saveTask(task);

        // 3. 楠岃瘉鏁版嵁
        List<Device> devices = deviceStorage.getAllDevices();
        Optional<Token> retrievedToken = deviceStorage.getToken("device-001");
        Optional<Task> retrievedTask = taskStorage.getTask("task-001");

        log.info("璁惧鏁伴噺: {}", devices.size());
        log.info("Token: {}", retrievedToken.map(Token::getTokenId).orElse("null"));
        log.info("Task: {}", retrievedTask.map(Task::getTid).orElse("null"));

        log.info("=== 鍐呭瓨瀛樺偍娴嬭瘯瀹屾垚 ===");
    }

    /**
     * 娴嬭瘯Redis瀛樺偍
     */
    public static void testRedisStorage() {
        log.info("=== 娴嬭瘯Redis瀛樺偍 ===");

        // 娉ㄦ剰锛歊edis瀛樺偍闇€瑕丷edis鏈嶅姟鍣ㄨ繍琛?
        // 杩欓噷鍙槸灞曠ず濡備綍鍒涘缓Redis瀛樺偍瀹炰緥

        try {
            // 1. 鍒涘缓Redis瀛樺偍瀹炰緥
            DeviceStorage deviceStorage = new RedisDeviceStorage();
            TaskStorage taskStorage = new RedisTaskStorage();
            RuleStorage ruleStorage = new RedisRuleStorage();

            log.info("Redis瀛樺偍瀹炰緥鍒涘缓鎴愬姛");

            // 2. 娣诲姞娴嬭瘯鏁版嵁
            Device device = new Device("device-002", "1.0.1", Arrays.asList("demoApp"));
            device.setDeviceGroupId("gb");

            Token token = new Token("token-002", "device-002", "gb");
            token.setStatus(TokenStatus.IDLE);

            User user = new User();
            user.setName("testUser2");

            Task task = new Task("task-002", "Test Task 2", "demoApp", "gb", 50, java.util.Map.of("textContent", "Test content 2"), user);

            deviceStorage.addDevice(device);
            deviceStorage.addToken("device-002", token);
            taskStorage.saveTask(task);

            // 3. 楠岃瘉鏁版嵁
            List<Device> devices = deviceStorage.getAllDevices();
            Optional<Token> retrievedToken = deviceStorage.getToken("device-002");
            Optional<Task> retrievedTask = taskStorage.getTask("task-002");

            log.info("璁惧鏁伴噺: {}", devices.size());
            log.info("Token: {}", retrievedToken.map(Token::getTokenId).orElse("null"));
            log.info("Task: {}", retrievedTask.map(Task::getTid).orElse("null"));

        } catch (Exception e) {
            log.warn("Redis瀛樺偍娴嬭瘯澶辫触锛屽彲鑳絉edis鏈嶅姟鍣ㄦ湭杩愯: {}", e.getMessage());
        }

        log.info("=== Redis瀛樺偍娴嬭瘯瀹屾垚 ===");
    }

    /**
     * 娴嬭瘯瀛樺偍宸ュ巶
     */
    public static void testStorageFactory() {
        log.info("=== 娴嬭瘯瀛樺偍宸ュ巶 ===");

        // 1. 浣跨敤宸ュ巶鍒涘缓鍐呭瓨瀛樺偍
        TaskStorage inMemoryStorage = TaskStorageFactory.createStorage("memory");
        log.info("鍐呭瓨瀛樺偍鍒涘缓鎴愬姛: {}", inMemoryStorage.getClass().getSimpleName());

        // 2. 浣跨敤宸ュ巶鍒涘缓Redis瀛樺偍
        try {
            TaskStorage redisStorage = TaskStorageFactory.createStorage("redis");
            log.info("Redis瀛樺偍鍒涘缓鎴愬姛: {}", redisStorage.getClass().getSimpleName());
        } catch (Exception e) {
            log.warn("Redis瀛樺偍鍒涘缓澶辫触: {}", e.getMessage());
        }

        // 3. 娴嬭瘯榛樿瀛樺偍
        TaskStorage defaultStorage = TaskStorageFactory.createStorage("unknown");
        log.info("榛樿瀛樺偍鍒涘缓鎴愬姛: {}", defaultStorage.getClass().getSimpleName());

        // 4. 浣跨敤瀛樺偍
        User user1 = new User();
        user1.setName("factoryUser1");

        User user2 = new User();
        user2.setName("factoryUser2");

        Task task1 = new Task("task-factory-001", "Factory Task 1", "demoApp", "us", 100, java.util.Map.of("textContent", "Factory content 1"), user1);
        Task task2 = new Task("task-factory-002", "Factory Task 2", "demoApp", "gb", 50, java.util.Map.of("textContent", "Factory content 2"), user2);

        inMemoryStorage.saveTask(task1);
        inMemoryStorage.saveTask(task2);

        List<Task> allTasks = inMemoryStorage.getAllTasks();
        log.info("瀛樺偍涓殑浠诲姟鏁伴噺: {}", allTasks.size());

        for (Task task : allTasks) {
            log.info("浠诲姟: {} (country: {}, project: {})",
                    task.getTid(), task.getTaskRoutingCountryCode(), task.getProject());
        }

        log.info("=== 瀛樺偍宸ュ巶娴嬭瘯瀹屾垚 ===");
    }
} 
