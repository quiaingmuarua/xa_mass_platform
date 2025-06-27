package com.xa.mass.engine.storage;

import com.xa.mass.engine.DeviceManager;
import com.xa.mass.eventbus.enums.device.DeviceStatus;
import com.xa.mass.eventbus.enums.task.TokenStatus;
import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Token;
import com.xa.mass.eventbus.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 设备存储使用示例
 * 展示如何使用不同的存储实现
 */
public class DeviceStorageExample {
    
    private static final Logger log = LoggerFactory.getLogger(DeviceStorageExample.class);
    
    public static void main(String[] args) {
        // 示例1：使用默认内存存储
        log.info("=== 使用默认内存存储 ===");
        DeviceManager memoryManager = new DeviceManager(); // 自动使用内存存储
        demonstrateDeviceManager(memoryManager);
        
        // 示例2：使用自定义内存存储
        log.info("\n=== 使用自定义内存存储 ===");
        DeviceStorage memoryStorage = TaskStorageFactory.createDefaultDeviceStorage();
        DeviceManager customMemoryManager = new DeviceManager(memoryStorage);
        demonstrateDeviceManager(customMemoryManager);
        
        // 示例3：使用Redis存储（需要Redis依赖）
        log.info("\n=== 使用Redis存储 ===");
        try {
            DeviceStorage redisStorage = TaskStorageFactory.createDeviceStorage(TaskStorageFactory.StorageType.REDIS);
            DeviceManager redisManager = new DeviceManager(redisStorage);
            demonstrateDeviceManager(redisManager);
        } catch (UnsupportedOperationException e) {
            log.warn("Redis存储未完全实现: {}", e.getMessage());
        }
        
        // 示例4：通过字符串配置创建存储
        log.info("\n=== 通过配置创建存储 ===");
        try {
            DeviceStorage configStorage = TaskStorageFactory.createDeviceStorage("memory");
            DeviceManager configManager = new DeviceManager(configStorage);
            demonstrateDeviceManager(configManager);
        } catch (Exception e) {
            log.error("配置创建存储失败: {}", e.getMessage());
        }
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
} 