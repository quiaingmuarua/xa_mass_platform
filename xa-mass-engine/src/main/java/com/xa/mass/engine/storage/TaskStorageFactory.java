package com.xa.mass.engine.storage;

import com.xa.mass.storage.api.RuleStorage;
import com.xa.mass.storage.api.TaskStorage;
import com.xa.mass.storage.api.WorkerStorage;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;

/**
 * Factory for engine storage implementations.
 *
 * <p>The active mainline is still memory-backed. Redis and database factory
 * paths remain explicit fail-fast placeholders until a queue-first runtime
 * storage model is implemented behind the same interfaces.</p>
 */
public final class TaskStorageFactory {

    private TaskStorageFactory() {
    }

    public static TaskStorage createTaskStorage(StorageType type) {
        return switch (type) {
            case MEMORY -> new InMemoryTaskStorage();
            case REDIS -> throw new UnsupportedOperationException(
                    "Redis task storage is not implemented yet; use MEMORY");
            case DATABASE -> throw new UnsupportedOperationException(
                    "Database task storage is not implemented yet; use MEMORY");
        };
    }

    public static RuleStorage createRuleStorage(StorageType type) {
        return switch (type) {
            case MEMORY -> new InMemoryRuleStorage();
            case REDIS -> throw new UnsupportedOperationException(
                    "Redis rule storage is not implemented yet; use MEMORY");
            case DATABASE -> throw new UnsupportedOperationException(
                    "Database rule storage is not implemented yet; use MEMORY");
        };
    }

    public static TaskStorage createDefaultTaskStorage() {
        return createTaskStorage(StorageType.MEMORY);
    }

    public static RuleStorage createDefaultRuleStorage() {
        return createRuleStorage(StorageType.MEMORY);
    }

    public static WorkerStorage createWorkerStorage(StorageType type) {
        return switch (type) {
            case MEMORY -> new InMemoryWorkerStorage();
            case REDIS -> throw new UnsupportedOperationException(
                    "Redis worker storage is not implemented yet; use MEMORY");
            case DATABASE -> throw new UnsupportedOperationException(
                    "Database worker storage is not implemented yet; use MEMORY");
        };
    }

    public static WorkerStorage createDefaultWorkerStorage() {
        return createWorkerStorage(StorageType.MEMORY);
    }

    public static WorkerStorage createWorkerStorage(String storageType) {
        try {
            StorageType type = StorageType.valueOf(storageType.toUpperCase());
            return createWorkerStorage(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported storage type: " + storageType);
        }
    }

    public static TaskStorage createTaskStorage(String storageType) {
        try {
            StorageType type = StorageType.valueOf(storageType.toUpperCase());
            return createTaskStorage(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported storage type: " + storageType);
        }
    }

    public static RuleStorage createRuleStorage(String storageType) {
        try {
            StorageType type = StorageType.valueOf(storageType.toUpperCase());
            return createRuleStorage(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported storage type: " + storageType);
        }
    }

    public enum StorageType {
        MEMORY,
        REDIS,
        DATABASE
    }
}
