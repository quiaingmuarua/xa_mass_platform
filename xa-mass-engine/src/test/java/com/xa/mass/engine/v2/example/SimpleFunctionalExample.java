package com.xa.mass.engine.v2.example;

import com.xa.mass.base.channel.queue.memory.InMemoryMessageMap;
import com.xa.mass.base.channel.queue.memory.InMemoryMessageQueue;
import com.xa.mass.base.channel.queue.api.MessageMap;
import com.xa.mass.base.channel.queue.api.MessageQueue;
import com.xa.mass.base.channel.queue.QueueProviderType;
import com.xa.mass.base.enums.Project;
import com.xa.mass.engine.v2.dao.TaskRepositoryManager;
import com.xa.mass.engine.v2.entity.DeviceEntity;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TaskMsgEntity;
import com.xa.mass.engine.v2.entity.TokenEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.xa.mass.engine.v2.util.QueueKeyUtil;

/**
 * 简化的函数式队列提供者使用示例
 */
public class SimpleFunctionalExample {

    private static final Logger logger = LoggerFactory.getLogger(SimpleFunctionalExample.class);

    public static void main(String[] args) {
        // 示例1: 基本使用
//        exampleBasicUsage();

        // 示例2: 注册自定义提供者
//        exampleCustomProvider();

        // 示例3: TaskRepositoryManager集成
        exampleTaskRepositoryManager();
    }

    /**
     * 示例1: 基本使用
     */
//    private static void exampleBasicUsage() {
//        logger.info("=== 示例1: 基本使用 ===");
//
//        // 使用默认内存队列
//        MessageQueue<String> queue = MessageQueue.createQueue(QueueProviderType.IN_MEMORY, "test-queue", String.class);
//        queue.offer("message1");
//        queue.offer("message2");
//
//        logger.info("队列大小: {}", queue.size());
//        logger.info("队列是否为空: {}", queue.isEmpty());
//    }


    /**
     * 示例3: TaskRepositoryManager集成
     */
    private static void exampleTaskRepositoryManager() {
        logger.info("=== 示例3: TaskRepositoryManager集成 ===");

        // 使用默认配置
        MessageMap<String, DeviceEntity> deviceMap = new InMemoryMessageMap<>("",DeviceEntity.class);
        
        TaskRepositoryManager defaultManager = TaskRepositoryManager.createWithDefaultProjects(QueueProviderType.IN_MEMORY);
        
        // 使用自定义队列类型
        TaskRepositoryManager customManager = TaskRepositoryManager.createWithDefaultProjects(QueueProviderType.IN_MEMORY, QueueProviderType.IN_MEMORY);

        // 演示任务操作
        TaskEntity task = new TaskEntity("task001", "测试任务", Project.DEMO_APP.getCode());
        defaultManager.saveTask(Project.DEMO_APP, task);

        // 创建队列并添加种子数据
        defaultManager.createSeedStream(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, task.getTaskId()));
        defaultManager.addSeed(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, task.getTaskId()), "seed-data-1");
        defaultManager.addSeed(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, task.getTaskId()), "seed-data-2");

        // 创建队列并添加任务消息
        defaultManager.createMsgStream(QueueKeyUtil.getMsgStreamKey(Project.DEMO_APP, task.getTaskId()));
        TaskMsgEntity taskMsg = new TaskMsgEntity("msg-task001", "task001");
        taskMsg.markAsBinding();
        defaultManager.addMsg(QueueKeyUtil.getMsgStreamKey(Project.DEMO_APP, task.getTaskId()), taskMsg);

        logger.info("任务 {} - 种子数: {}, 消息数: {}", 
            task.getTaskName(), defaultManager.getSeedCount(QueueKeyUtil.getSeedStreamKey(Project.DEMO_APP, task.getTaskId())), defaultManager.getMsgCount(QueueKeyUtil.getMsgStreamKey(Project.DEMO_APP, task.getTaskId())));
    }

    /**
     * 创建并测试任务的辅助方法
     */
    private static void createAndTestTask(TaskRepositoryManager manager, String taskId, String taskName) {
        TaskEntity task = new TaskEntity(taskId, taskName, Project.DEMO_APP.getCode());
        manager.saveTask(Project.DEMO_APP, task);

        // 添加种子数据
        manager.addSeed(taskId, "seed-data-1");
        manager.addSeed(taskId, "seed-data-2");

        // 添加任务消息
        TaskMsgEntity taskMsg = new TaskMsgEntity("msg-" + taskId, taskId);
        taskMsg.markAsBinding();
        manager.addMsg(taskId, taskMsg);

        logger.info("任务 {} - 种子数: {}, 消息数: {}", 
            taskName, manager.getSeedCount(taskId), manager.getMsgCount(taskId));
    }
} 