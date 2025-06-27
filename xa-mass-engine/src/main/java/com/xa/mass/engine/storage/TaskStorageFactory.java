package com.xa.mass.engine.storage;

/**
 * 存储工厂
 * 负责创建和管理不同的存储实现
 */
public class TaskStorageFactory {
    
    /**
     * 存储类型枚举
     */
    public enum StorageType {
        MEMORY,
        REDIS,
        DATABASE
    }
    
    /**
     * 创建任务存储实例
     * 
     * @param type 存储类型
     * @return 任务存储实例
     */
    public static TaskStorage createTaskStorage(StorageType type) {
        switch (type) {
            case MEMORY:
                return new InMemoryTaskStorage();
            case REDIS:
                return new RedisTaskStorage();
            case DATABASE:
                // TODO: 实现数据库存储
                throw new UnsupportedOperationException("Database storage not implemented yet");
            default:
                throw new IllegalArgumentException("Unsupported storage type: " + type);
        }
    }
    
    /**
     * 创建设备存储实例
     * 
     * @param type 存储类型
     * @return 设备存储实例
     */
    public static DeviceStorage createDeviceStorage(StorageType type) {
        switch (type) {
            case MEMORY:
                return new InMemoryDeviceStorage();
            case REDIS:
                return new RedisDeviceStorage();
            case DATABASE:
                // TODO: 实现数据库存储
                throw new UnsupportedOperationException("Database storage not implemented yet");
            default:
                throw new IllegalArgumentException("Unsupported storage type: " + type);
        }
    }
    
    /**
     * 创建规则存储实例
     * 
     * @param type 存储类型
     * @return 规则存储实例
     */
    public static RuleStorage createRuleStorage(StorageType type) {
        switch (type) {
            case MEMORY:
                return new InMemoryRuleStorage();
            case REDIS:
                return new RedisRuleStorage();
            case DATABASE:
                // TODO: 实现数据库存储
                throw new UnsupportedOperationException("Database storage not implemented yet");
            default:
                throw new IllegalArgumentException("Unsupported storage type: " + type);
        }
    }
    
    /**
     * 创建默认任务存储实例（内存存储）
     * 
     * @return 内存任务存储实例
     */
    public static TaskStorage createDefaultTaskStorage() {
        return createTaskStorage(StorageType.MEMORY);
    }
    
    /**
     * 创建默认设备存储实例（内存存储）
     * 
     * @return 内存设备存储实例
     */
    public static DeviceStorage createDefaultDeviceStorage() {
        return createDeviceStorage(StorageType.MEMORY);
    }
    
    /**
     * 创建默认规则存储实例（内存存储）
     * 
     * @return 内存规则存储实例
     */
    public static RuleStorage createDefaultRuleStorage() {
        return createRuleStorage(StorageType.MEMORY);
    }
    
    /**
     * 根据配置创建任务存储实例
     * 
     * @param storageType 存储类型字符串
     * @return 任务存储实例
     */
    public static TaskStorage createTaskStorage(String storageType) {
        try {
            StorageType type = StorageType.valueOf(storageType.toUpperCase());
            return createTaskStorage(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported storage type: " + storageType);
        }
    }
    
    /**
     * 根据配置创建设备存储实例
     * 
     * @param storageType 存储类型字符串
     * @return 设备存储实例
     */
    public static DeviceStorage createDeviceStorage(String storageType) {
        try {
            StorageType type = StorageType.valueOf(storageType.toUpperCase());
            return createDeviceStorage(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported storage type: " + storageType);
        }
    }
    
    /**
     * 根据配置创建规则存储实例
     * 
     * @param storageType 存储类型字符串
     * @return 规则存储实例
     */
    public static RuleStorage createRuleStorage(String storageType) {
        try {
            StorageType type = StorageType.valueOf(storageType.toUpperCase());
            return createRuleStorage(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported storage type: " + storageType);
        }
    }
    
    // 向后兼容的方法
    /**
     * @deprecated 使用 createTaskStorage 替代
     */
    @Deprecated
    public static TaskStorage createStorage(StorageType type) {
        return createTaskStorage(type);
    }
    
    /**
     * @deprecated 使用 createDefaultTaskStorage 替代
     */
    @Deprecated
    public static TaskStorage createDefaultStorage() {
        return createDefaultTaskStorage();
    }
    
    /**
     * @deprecated 使用 createTaskStorage 替代
     */
    @Deprecated
    public static TaskStorage createStorage(String storageType) {
        return createTaskStorage(storageType);
    }
} 