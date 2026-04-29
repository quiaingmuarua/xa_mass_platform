package com.xa.mass.engine.storage;

import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskStorageFactoryTest {

    // ---- MEMORY returns usable instances ----

    @Test
    void memoryTaskStorageIsCreated() {
        assertInstanceOf(InMemoryTaskStorage.class,
                TaskStorageFactory.createTaskStorage(TaskStorageFactory.StorageType.MEMORY));
    }

    @Test
    void memoryWorkerStorageIsCreated() {
        assertInstanceOf(InMemoryWorkerStorage.class,
                TaskStorageFactory.createWorkerStorage(TaskStorageFactory.StorageType.MEMORY));
    }

    @Test
    void memoryRuleStorageIsCreated() {
        assertInstanceOf(InMemoryRuleStorage.class,
                TaskStorageFactory.createRuleStorage(TaskStorageFactory.StorageType.MEMORY));
    }

    // ---- REDIS fails fast at factory time ----

    @Test
    void redisTaskStorageThrowsAtCreation() {
        assertThrows(UnsupportedOperationException.class,
                () -> TaskStorageFactory.createTaskStorage(TaskStorageFactory.StorageType.REDIS),
                "REDIS task storage must fail at factory time, not silently return a broken object");
    }

    @Test
    void redisWorkerStorageThrowsAtCreation() {
        assertThrows(UnsupportedOperationException.class,
                () -> TaskStorageFactory.createWorkerStorage(TaskStorageFactory.StorageType.REDIS));
    }

    @Test
    void redisRuleStorageThrowsAtCreation() {
        assertThrows(UnsupportedOperationException.class,
                () -> TaskStorageFactory.createRuleStorage(TaskStorageFactory.StorageType.REDIS));
    }

    // ---- DATABASE fails fast ----

    @Test
    void databaseTaskStorageThrowsAtCreation() {
        assertThrows(UnsupportedOperationException.class,
                () -> TaskStorageFactory.createTaskStorage(TaskStorageFactory.StorageType.DATABASE));
    }

    // ---- defaults resolve to MEMORY ----

    @Test
    void defaultStoragesAreInMemory() {
        assertInstanceOf(InMemoryTaskStorage.class, TaskStorageFactory.createDefaultTaskStorage());
        assertInstanceOf(InMemoryWorkerStorage.class, TaskStorageFactory.createDefaultWorkerStorage());
        assertInstanceOf(InMemoryRuleStorage.class, TaskStorageFactory.createDefaultRuleStorage());
    }

    // ---- string-based factory ----

    @Test
    void stringMemoryResolves() {
        assertInstanceOf(InMemoryTaskStorage.class,
                TaskStorageFactory.createTaskStorage("memory"));
    }

    @Test
    void stringUnknownTypeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskStorageFactory.createTaskStorage("unknown_type"));
    }
}
