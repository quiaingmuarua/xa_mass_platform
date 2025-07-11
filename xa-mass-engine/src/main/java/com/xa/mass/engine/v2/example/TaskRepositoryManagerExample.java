package com.xa.mass.engine.v2.example;

import com.xa.mass.base.channel.queue.InMemoryMessageMap;
import com.xa.mass.base.channel.queue.MessageMap;
import com.xa.mass.base.channel.queue.MessageQueueFactory;
import com.xa.mass.engine.v2.config.QueueConfig;
import com.xa.mass.engine.v2.dao.TaskRepositoryManager;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TaskMsgEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TaskRepositoryManager 使用示例
 * 展示如何使用工厂模式和配置来创建不同类型的队列
 */
public class TaskRepositoryManagerExample {

    private static final Logger logger = LoggerFactory.getLogger(TaskRepositoryManagerExample.class);

    public static void main(String[] args) {
        // 示例1: 使用默认配置（内存队列）
        exampleDefaultConfiguration();

        // 示例2: 使用自定义队列类型
        exampleCustomQueueType();

        // 示例3: 使用详细配置
        exampleDetailedConfiguration();

        // 示例4: 不同环境配置
        exampleEnvironmentConfigurations();
    }

    /**
     * 示例1: 使用默认配置
     */
    private static void exampleDefaultConfiguration() {
        logger.info("=== 示例1: 使用默认配置 ===");

        // 创建任务存储
        MessageMap<String, TaskEntity> taskMap = new InMemoryMessageMap<>();
        
        // 使用默认配置创建管理器（内存队列）
        TaskRepositoryManager manager = new TaskRepositoryManager(taskMap);

        // 创建任务
        TaskEntity task = new TaskEntity();
        task.setTaskId("task-001");
        task.setTaskName("示例任务");
        manager.createTask(task);

        // 添加种子数据
        manager.addTaskSeed("task-001", "seed-data-1");
        manager.addTaskSeed("task-001", "seed-data-2");

        // 获取种子数据
        String seed = manager.getTaskSeed("task-001");
        logger.info("获取到的种子: {}", seed);

        logger.info("任务种子数量: {}", manager.getTaskSeedCount("task-001"));
    }

    /**
     * 示例2: 使用自定义队列类型
     */
    private static void exampleCustomQueueType() {
        logger.info("=== 示例2: 使用自定义队列类型 ===");

        MessageMap<String, TaskEntity> taskMap = new InMemoryMessageMap<>();
        
        // 指定使用内存队列类型
        TaskRepositoryManager manager = new TaskRepositoryManager(
            taskMap, 
            MessageQueueFactory.QueueType.IN_MEMORY
        );

        TaskEntity task = new TaskEntity();
        task.setTaskId("task-002");
        task.setTaskName("自定义队列任务");
        manager.createTask(task);

        // 添加任务消息
        TaskMsgEntity taskMsg = new TaskMsgEntity("msg-001", "task-002");
        taskMsg.markAsBinding();
        manager.addTaskMsg("task-002", taskMsg);

        // 获取任务消息
        TaskMsgEntity receivedMsg = manager.getTaskMsg("task-002");
        logger.info("获取到的任务消息: {}", receivedMsg);

        logger.info("任务消息数量: {}", manager.getTaskMsgCount("task-002"));
    }

    /**
     * 示例3: 使用详细配置
     */
    private static void exampleDetailedConfiguration() {
        logger.info("=== 示例3: 使用详细配置 ===");

        MessageMap<String, TaskEntity> taskMap = new InMemoryMessageMap<>();
        
        // 创建详细配置
        QueueConfig config = new QueueConfig();
        config.setTaskSeedQueueType(MessageQueueFactory.QueueType.IN_MEMORY);
        config.setTaskMsgQueueType(MessageQueueFactory.QueueType.IN_MEMORY);
        
        TaskRepositoryManager manager = new TaskRepositoryManager(taskMap, config);

        TaskEntity task = new TaskEntity();
        task.setTaskId("task-003");
        task.setTaskName("详细配置任务");
        manager.createTask(task);

        // 批量添加种子
        for (int i = 1; i <= 5; i++) {
            manager.addTaskSeed("task-003", "seed-" + i);
        }

        // 批量添加消息
        for (int i = 1; i <= 3; i++) {
            TaskMsgEntity taskMsg = new TaskMsgEntity("msg-" + i, "task-003");
            taskMsg.markAsBinding();
            manager.addTaskMsg("task-003", taskMsg);
        }

        logger.info("种子数量: {}, 消息数量: {}", 
            manager.getTaskSeedCount("task-003"), 
            manager.getTaskMsgCount("task-003"));
    }

    /**
     * 示例4: 不同环境配置
     */
    private static void exampleEnvironmentConfigurations() {
        logger.info("=== 示例4: 不同环境配置 ===");

        MessageMap<String, TaskEntity> taskMap = new InMemoryMessageMap<>();

        // 开发环境配置
        QueueConfig devConfig = QueueConfig.createDevelopment();
        TaskRepositoryManager devManager = new TaskRepositoryManager(taskMap, devConfig);
        logger.info("开发环境配置: {}", devConfig.getDefaultQueueType());

        // 测试环境配置
        QueueConfig testConfig = QueueConfig.createTest();
        TaskRepositoryManager testManager = new TaskRepositoryManager(taskMap, testConfig);
        logger.info("测试环境配置: {}", testConfig.getDefaultQueueType());

        // 生产环境配置
        QueueConfig prodConfig = QueueConfig.createProduction();
        TaskRepositoryManager prodManager = new TaskRepositoryManager(taskMap, prodConfig);
        logger.info("生产环境配置: {}", prodConfig.getDefaultQueueType());

        // 演示不同环境下的任务创建
        createTaskInManager(devManager, "dev-task-001", "开发环境任务");
        createTaskInManager(testManager, "test-task-001", "测试环境任务");
        createTaskInManager(prodManager, "prod-task-001", "生产环境任务");
    }

    /**
     * 在指定管理器中创建任务的辅助方法
     */
    private static void createTaskInManager(TaskRepositoryManager manager, String taskId, String taskName) {
        TaskEntity task = new TaskEntity();
        task.setTaskId(taskId);
        task.setTaskName(taskName);
        manager.createTask(task);
        
        // 添加一些测试数据
        manager.addTaskSeed(taskId, "test-seed");
        
        TaskMsgEntity taskMsg = new TaskMsgEntity("msg-" + taskId, taskId);
        taskMsg.markAsBinding();
        manager.addTaskMsg(taskId, taskMsg);
        
        logger.info("在 {} 中创建任务: {}, 种子数: {}, 消息数: {}", 
            manager.getClass().getSimpleName(),
            taskId,
            manager.getTaskSeedCount(taskId),
            manager.getTaskMsgCount(taskId));
    }
} 