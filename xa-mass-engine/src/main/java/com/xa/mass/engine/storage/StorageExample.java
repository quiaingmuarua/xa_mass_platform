package com.xa.mass.engine.storage;

import com.xa.mass.eventbus.model.Task;
import com.xa.mass.eventbus.model.TaskMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 存储使用示例
 * 展示如何使用不同的存储实现
 */
public class StorageExample {
    
    private static final Logger log = LoggerFactory.getLogger(StorageExample.class);
    
    public static void main(String[] args) {
        // 示例1：使用默认内存存储
        log.info("=== 使用默认内存存储 ===");
        TaskStorage storage = TaskStorageFactory.createDefaultStorage();
        demonstrateStorage(storage);
        
        // 示例2：使用Redis存储（需要Redis依赖）
        log.info("\n=== 使用Redis存储 ===");
        try {
            TaskStorage redisStorage = TaskStorageFactory.createStorage(TaskStorageFactory.StorageType.REDIS);
            demonstrateStorage(redisStorage);
        } catch (UnsupportedOperationException e) {
            log.warn("Redis存储未完全实现: {}", e.getMessage());
        }
        
        // 示例3：通过字符串配置创建存储
        log.info("\n=== 通过配置创建存储 ===");
        try {
            TaskStorage configStorage = TaskStorageFactory.createStorage("memory");
            demonstrateStorage(configStorage);
        } catch (Exception e) {
            log.error("配置创建存储失败: {}", e.getMessage());
        }
    }
    
    private static void demonstrateStorage(TaskStorage storage) {
        try {
            log.info("存储类型: {}", storage.getClass().getSimpleName());
            log.info("存储操作演示完成");
        } catch (Exception e) {
            log.error("存储操作失败: {}", e.getMessage());
        }
    }
} 