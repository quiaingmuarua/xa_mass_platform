package com.xa.mass.engine.v2.example;

import com.xa.mass.base.channel.messaging.MessageMapProviderRegistry;
import com.xa.mass.base.channel.messaging.MessageProviderType;
import com.xa.mass.base.channel.messaging.api.MessageMap;
import com.xa.mass.base.enums.Project;
import com.xa.mass.engine.v2.dao.TaskRepositoryManager;
import com.xa.mass.engine.v2.entity.TaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MessageMapProviderRegistry 使用示例
 * 展示如何根据不同的 MessageProviderType 创建不同类型的 MessageMap
 */
public class MessageMapProviderExample {
    
    private static final Logger logger = LoggerFactory.getLogger(MessageMapProviderExample.class);

    public static void main(String[] args) {
        // 示例1: 基本使用
        exampleBasicUsage();

        // 示例2: 不同队列类型
        exampleDifferentQueueTypes();

        // 示例3: TaskRepositoryManager 集成
        exampleTaskRepositoryManagerIntegration();
    }

    /**
     * 示例1: 基本使用
     */
    private static void exampleBasicUsage() {
        logger.info("=== 示例1: 基本使用 ===");

        // 创建内存映射
        MessageMap<String, TaskEntity> memoryMap = MessageMapProviderRegistry.createMap(
            MessageProviderType.IN_MEMORY, "task-map", TaskEntity.class);
        
        // 基本操作
        TaskEntity task = new TaskEntity();
        task.setTaskId("task001");
        task.setProject(Project.DEMO_APP.getCode());
        
        memoryMap.put("task001", task);
        TaskEntity retrieved = memoryMap.get("task001");
        
        logger.info("内存映射操作成功: taskId={}, project={}", 
                   retrieved.getTaskId(), retrieved.getProject());
    }

    /**
     * 示例2: 不同队列类型
     */
    private static void exampleDifferentQueueTypes() {
        logger.info("=== 示例2: 不同队列类型 ===");

        // 内存类型
        MessageMap<String, TaskEntity> memoryMap = MessageMapProviderRegistry.createMap(
            MessageProviderType.IN_MEMORY, "memory-task-map", TaskEntity.class);
        logger.info("创建内存映射: {}", memoryMap.getClass().getSimpleName());

        // Redis 类型（需要先初始化 Redis 连接）
        try {
            // 这里会抛出异常，因为 Redis 连接未初始化
            MessageMap<String, TaskEntity> redisMap = MessageMapProviderRegistry.createMap(
                MessageProviderType.REDIS, "redis-task-map", TaskEntity.class);
            logger.info("创建 Redis 映射: {}", redisMap.getClass().getSimpleName());
        } catch (Exception e) {
            logger.info("Redis 映射创建失败（预期行为）: {}", e.getMessage());
        }

        // 测试不支持的类型
        try {
            MessageMap<String, TaskEntity> kafkaMap = MessageMapProviderRegistry.createMap(
                MessageProviderType.KAFKA, "kafka-task-map", TaskEntity.class);
        } catch (Exception e) {
            logger.info("Kafka 映射创建失败（预期行为）: {}", e.getMessage());
        }
    }

    /**
     * 示例3: TaskRepositoryManager 集成
     */
    private static void exampleTaskRepositoryManagerIntegration() {
        logger.info("=== 示例3: TaskRepositoryManager 集成 ===");

        // 使用内存队列类型
        TaskRepositoryManager memoryManager = TaskRepositoryManager.createWithDefaultProjects(
            MessageProviderType.IN_MEMORY, MessageProviderType.IN_MEMORY);
        
        // 创建任务
        TaskEntity task = new TaskEntity();
        task.setTaskId("task002");
        task.setProject(Project.DEMO_APP.getCode());
        
        memoryManager.saveTask(Project.DEMO_APP, task);
        TaskEntity retrieved = memoryManager.getTask(Project.DEMO_APP, "task002");
        
        logger.info("内存管理器操作成功: taskId={}, project={}", 
                   retrieved.getTaskId(), retrieved.getProject());

        // 使用 Redis 队列类型（需要先初始化 Redis 连接）
        try {
            TaskRepositoryManager redisManager = TaskRepositoryManager.createWithDefaultProjects(
                MessageProviderType.REDIS, MessageProviderType.REDIS);
            
            TaskEntity redisTask = new TaskEntity();
            redisTask.setTaskId("task003");
            redisTask.setProject(Project.DEMO_APP.getCode());
            
            redisManager.saveTask(Project.DEMO_APP, redisTask);
            TaskEntity redisRetrieved = redisManager.getTask(Project.DEMO_APP, "task003");
            
            logger.info("Redis 管理器操作成功: taskId={}, project={}", 
                       redisRetrieved.getTaskId(), redisRetrieved.getProject());
        } catch (Exception e) {
            logger.info("Redis 管理器操作失败（预期行为）: {}", e.getMessage());
        }

        // 混合使用：内存映射 + Redis 流
        try {
            TaskRepositoryManager mixedManager = TaskRepositoryManager.createWithDefaultProjects(
                MessageProviderType.IN_MEMORY,  // 任务存储使用内存
                MessageProviderType.REDIS       // 消息流使用 Redis
            );
            
            TaskEntity mixedTask = new TaskEntity();
            mixedTask.setTaskId("task004");
            mixedTask.setProject(Project.DEMO_APP.getCode());
            
            mixedManager.saveTask(Project.DEMO_APP, mixedTask);
            TaskEntity mixedRetrieved = mixedManager.getTask(Project.DEMO_APP, "task004");
            
            logger.info("混合管理器操作成功: taskId={}, project={}", 
                       mixedRetrieved.getTaskId(), mixedRetrieved.getProject());
        } catch (Exception e) {
            logger.info("混合管理器操作失败（预期行为）: {}", e.getMessage());
        }
    }

    /**
     * 示例4: 项目隔离
     */
    private static void exampleProjectIsolation() {
        logger.info("=== 示例4: 项目隔离 ===");

        TaskRepositoryManager manager = TaskRepositoryManager.createWithDefaultProjects(
            MessageProviderType.IN_MEMORY, MessageProviderType.IN_MEMORY);

        // 为不同项目创建任务
        for (Project project : Project.values()) {
            TaskEntity task = new TaskEntity();
            task.setTaskId("task-" + project.name());
            task.setProject(project.getCode());
            
            manager.saveTask(project, task);
            logger.info("为项目 {} 创建任务: {}", project, task.getTaskId());
        }

        // 验证项目隔离
        for (Project project : Project.values()) {
            TaskEntity task = manager.getTask(project, "task-" + project.name());
            logger.info("项目 {} 的任务: {}", project, task.getTaskId());
            
            // 验证任务数量
            int count = manager.getProjectTaskCount(project);
            logger.info("项目 {} 的任务数量: {}", project, count);
        }

        logger.info("总任务数量: {}", manager.getTotalTaskCount());
    }
} 