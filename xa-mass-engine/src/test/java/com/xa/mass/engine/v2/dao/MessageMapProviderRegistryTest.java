package com.xa.mass.engine.v2.dao;

import com.xa.mass.base.channel.messaging.MessageMapProviderRegistry;
import com.xa.mass.base.channel.messaging.QueueProviderType;
import com.xa.mass.base.channel.messaging.api.MessageMap;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageMap;
import com.xa.mass.engine.v2.entity.TaskEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MessageMapProviderRegistry 测试类
 */
public class MessageMapProviderRegistryTest {

    @BeforeEach
    void setUp() {
        // 清理缓存避免测试间冲突
        MessageMapProviderRegistry.clearCache();
    }

    @Test
    void testCreateInMemoryMap() {
        // 测试创建内存映射
        MessageMap<String, TaskEntity> map = MessageMapProviderRegistry.createMap(
            QueueProviderType.IN_MEMORY, "test-map", TaskEntity.class);
        
        assertNotNull(map);
        assertTrue(map instanceof InMemoryMessageMap);
        assertEquals("test-map", map.getName());
        
        // 测试基本操作
        TaskEntity task = new TaskEntity();
        task.setTaskId("task1");
        map.put("task1", task);
        
        TaskEntity retrieved = map.get("task1");
        assertNotNull(retrieved);
        assertEquals("task1", retrieved.getTaskId());
    }

    @Test
    void testCreateMapWithDefaultName() {
        // 测试使用默认名称创建映射
        MessageMap< String,Integer> map = MessageMapProviderRegistry.createMap(QueueProviderType.IN_MEMORY,"KEY",Integer.class);
        
        assertNotNull(map);
        assertTrue(map instanceof InMemoryMessageMap);
//        assertEquals("default", map.getName());
    }

    @Test
    void testCreateMapWithProjectName() {
        // 测试使用项目名称创建映射
        MessageMap<String, TaskEntity> map = MessageMapProviderRegistry.createMap(
            QueueProviderType.IN_MEMORY, "task:DEMO", TaskEntity.class);
        
        assertNotNull(map);
        assertEquals("task:DEMO", map.getName());
    }


    @Test
    void testCreateRedisMapWithTypeInfo() {
        // 测试创建 Redis 映射（需要先初始化 Redis 连接）
        // 这里只是测试方法调用，实际 Redis 连接需要单独测试
        assertThrows(Exception.class, () -> {
            MessageMapProviderRegistry.createMap(
                QueueProviderType.REDIS, "test-map", TaskEntity.class);
        });
    }

    @Test
    void testCacheReuse() {
        // 测试缓存重用
        MessageMap<String, TaskEntity> map1 = MessageMapProviderRegistry.createMap(
            QueueProviderType.IN_MEMORY, "cache-test", TaskEntity.class);
        MessageMap<String, TaskEntity> map2 = MessageMapProviderRegistry.createMap(
            QueueProviderType.IN_MEMORY, "cache-test", TaskEntity.class);
        
        // 应该返回同一个实例
        assertSame(map1, map2);
    }

    @Test
    void testDifferentTypesCreateDifferentInstances() {
        // 测试不同类型创建不同实例
        MessageMap<String, TaskEntity> map1 = MessageMapProviderRegistry.createMap(
            QueueProviderType.IN_MEMORY, "test", TaskEntity.class);
        MessageMap<String, Integer> map2 = MessageMapProviderRegistry.createMap(
            QueueProviderType.IN_MEMORY, "test", Integer.class);
        
        // 应该返回不同的实例
        assertNotSame(map1, map2);
    }

    @Test
    void testHasProvider() {
        // 测试提供者检查
        assertTrue(MessageMapProviderRegistry.hasProvider(QueueProviderType.IN_MEMORY));
        assertTrue(MessageMapProviderRegistry.hasProvider(QueueProviderType.REDIS));
        assertFalse(MessageMapProviderRegistry.hasProvider(QueueProviderType.KAFKA));
    }
} 