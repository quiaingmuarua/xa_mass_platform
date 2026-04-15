package com.xa.mass.engine.storage;

/**
 * 存储工厂
 * 负责创建和管理不同的存储实现
 */
public class TaskStorageFactory {

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
                throw new UnsupportedOperationException("Redis task storage is not implemented yet; use MEMORY");
            case DATABASE:
                throw new UnsupportedOperationException("Database task storage is not implemented yet; use MEMORY");
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
                throw new UnsupportedOperationException("Redis rule storage is not implemented yet; use MEMORY");
            case DATABASE:
                throw new UnsupportedOperationException("Database rule storage is not implemented yet; use MEMORY");
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
     * 创建默认规则存储实例（内存存储）
     *
     * @return 内存规则存储实例
     */
    public static RuleStorage createDefaultRuleStorage() {
        return createRuleStorage(StorageType.MEMORY);
    }

    /**
     * 创建 Worker 存储实例
     *
     * @param type 存储类型
     * @return Worker 存储实例
     */
    public static WorkerStorage createWorkerStorage(StorageType type) {
        switch (type) {
            case MEMORY:
                return new InMemoryWorkerStorage();
            case REDIS:
                throw new UnsupportedOperationException("Redis worker storage is not implemented yet; use MEMORY");
            case DATABASE:
                throw new UnsupportedOperationException("Database worker storage is not implemented yet; use MEMORY");
            default:
                throw new IllegalArgumentException("Unsupported storage type: " + type);
        }
    }

    /**
     * 创建默认 Worker 存储实例（内存存储）
     *
     * @return 内存 Worker 存储实例
     */
    public static WorkerStorage createDefaultWorkerStorage() {
        return createWorkerStorage(StorageType.MEMORY);
    }

    /**
     * 根据配置创建 Worker 存储实例
     *
     * @param storageType 存储类型字符串
     * @return Worker 存储实例
     */
    public static WorkerStorage createWorkerStorage(String storageType) {
        try {
            StorageType type = StorageType.valueOf(storageType.toUpperCase());
            return createWorkerStorage(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported storage type: " + storageType);
        }
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

    /**
     * @deprecated 使用 createTaskStorage 替代
     */
    @Deprecated
    public static TaskStorage createStorage(StorageType type) {
        return createTaskStorage(type);
    }

    // 向后兼容的方法

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

    /**
     * 存储类型枚举
     */
    public enum StorageType {
        MEMORY,
        REDIS,
        DATABASE
    }
} 