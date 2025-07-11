package com.xa.mass.engine.v2.example;

import com.xa.mass.base.channel.queue.memory.InMemoryMessageMap;
import com.xa.mass.base.channel.queue.memory.InMemoryMessageQueue;
import com.xa.mass.base.channel.queue.api.MessageMap;
import com.xa.mass.base.channel.queue.api.MessageQueue;
import com.xa.mass.base.channel.queue.MessageQueueProviderRegistry;
import com.xa.mass.base.channel.queue.QueueProviderType;
import com.xa.mass.engine.v2.dao.TaskRepositoryManager;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TaskMsgEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 简化的函数式队列提供者使用示例
 */
public class SimpleFunctionalExample {

    private static final Logger logger = LoggerFactory.getLogger(SimpleFunctionalExample.class);

    public static void main(String[] args) {
        // 示例1: 基本使用
        exampleBasicUsage();

        // 示例2: 注册自定义提供者
        exampleCustomProvider();

        // 示例3: TaskRepositoryManager集成
        exampleTaskRepositoryManager();
    }

    /**
     * 示例1: 基本使用
     */
    private static void exampleBasicUsage() {
        logger.info("=== 示例1: 基本使用 ===");

        // 使用默认内存队列
        MessageQueue<String> queue = MessageQueueProviderRegistry.createQueue(QueueProviderType.IN_MEMORY, "test-queue");
        queue.offer("message1");
        queue.offer("message2");

        logger.info("队列大小: {}", queue.size());
        logger.info("队列是否为空: {}", queue.isEmpty());
    }

    /**
     * 示例2: 注册自定义提供者
     */
    private static void exampleCustomProvider() {
        logger.info("=== 示例2: 注册自定义提供者 ===");

        // 注册一个自定义的内存队列提供者
        // MessageQueueProviderRegistry.register("custom", name -> {
        //     logger.info("创建自定义队列: {}", name);
        //     return new InMemoryMessageQueue<>();
        // });
        // 由于 QueueProviderType 没有 CUSTOM，如需自定义请扩展枚举或用 IN_MEMORY 演示
        // 这里用 IN_MEMORY 演示
        MessageQueueProviderRegistry.register(QueueProviderType.IN_MEMORY, name -> {
            logger.info("创建自定义队列: {}", name);
            return new InMemoryMessageQueue<>();
        });

        // 使用自定义提供者
        MessageQueue<String> queue2 = MessageQueueProviderRegistry.createQueue(QueueProviderType.IN_MEMORY, "my-queue");
        queue2.offer("test message");
        
        logger.info("自定义队列大小: {}", queue2.size());
    }

    /**
     * 示例3: TaskRepositoryManager集成
     */
    private static void exampleTaskRepositoryManager() {
        logger.info("=== 示例3: TaskRepositoryManager集成 ===");

        MessageMap<String, TaskEntity> taskMap = new InMemoryMessageMap<>();

        // 使用默认配置（内存队列）
        TaskRepositoryManager manager1 = new TaskRepositoryManager(taskMap, QueueProviderType.IN_MEMORY);
        createAndTestTask(manager1, "task-001", "默认配置任务");

        // 使用自定义队列类型
        TaskRepositoryManager manager2 = new TaskRepositoryManager(
            taskMap, 
            QueueProviderType.IN_MEMORY,  // 种子队列使用内存队列
            QueueProviderType.IN_MEMORY   // 消息队列使用内存队列
        );
        createAndTestTask(manager2, "task-002", "自定义队列任务");
    }

    /**
     * 创建并测试任务的辅助方法
     */
    private static void createAndTestTask(TaskRepositoryManager manager, String taskId, String taskName) {
        // 创建任务
        TaskEntity task = new TaskEntity();
        task.setTaskId(taskId);
        task.setTaskName(taskName);
        manager.createTask(task);

        // 添加种子数据
        manager.addTaskSeed(taskId, "seed-data-1");
        manager.addTaskSeed(taskId, "seed-data-2");

        // 添加任务消息
        TaskMsgEntity taskMsg = new TaskMsgEntity("msg-" + taskId, taskId);
        taskMsg.markAsBinding();
        manager.addTaskMsg(taskId, taskMsg);

        logger.info("任务 {} - 种子数: {}, 消息数: {}", 
            taskName, manager.getTaskSeedCount(taskId), manager.getTaskMsgCount(taskId));
    }
} 