package com.xa.mass.engine.v2.config;

import com.xa.mass.base.channel.queue.MessageQueueFactory;

/**
 * 队列配置类
 * 用于配置TaskRepositoryManager等组件使用的队列类型
 */
public class QueueConfig {

    private MessageQueueFactory.QueueType defaultQueueType = MessageQueueFactory.QueueType.IN_MEMORY;
    
    // 任务种子队列类型
    private MessageQueueFactory.QueueType taskSeedQueueType = MessageQueueFactory.QueueType.IN_MEMORY;
    
    // 任务消息队列类型
    private MessageQueueFactory.QueueType taskMsgQueueType = MessageQueueFactory.QueueType.IN_MEMORY;

    public QueueConfig() {
    }

    public QueueConfig(MessageQueueFactory.QueueType defaultQueueType) {
        this.defaultQueueType = defaultQueueType;
        this.taskSeedQueueType = defaultQueueType;
        this.taskMsgQueueType = defaultQueueType;
    }

    /**
     * 获取默认队列类型
     */
    public MessageQueueFactory.QueueType getDefaultQueueType() {
        return defaultQueueType;
    }

    /**
     * 设置默认队列类型
     */
    public void setDefaultQueueType(MessageQueueFactory.QueueType defaultQueueType) {
        this.defaultQueueType = defaultQueueType;
    }

    /**
     * 获取任务种子队列类型
     */
    public MessageQueueFactory.QueueType getTaskSeedQueueType() {
        return taskSeedQueueType;
    }

    /**
     * 设置任务种子队列类型
     */
    public void setTaskSeedQueueType(MessageQueueFactory.QueueType taskSeedQueueType) {
        this.taskSeedQueueType = taskSeedQueueType;
    }

    /**
     * 获取任务消息队列类型
     */
    public MessageQueueFactory.QueueType getTaskMsgQueueType() {
        return taskMsgQueueType;
    }

    /**
     * 设置任务消息队列类型
     */
    public void setTaskMsgQueueType(MessageQueueFactory.QueueType taskMsgQueueType) {
        this.taskMsgQueueType = taskMsgQueueType;
    }

    /**
     * 创建开发环境配置
     */
    public static QueueConfig createDevelopment() {
        return new QueueConfig(MessageQueueFactory.QueueType.IN_MEMORY);
    }

    /**
     * 创建生产环境配置
     */
    public static QueueConfig createProduction() {
        QueueConfig config = new QueueConfig();
        // 生产环境可以根据需要配置不同的队列类型
        // 例如：config.setTaskSeedQueueType(MessageQueueFactory.QueueType.REDIS);
        return config;
    }

    /**
     * 创建测试环境配置
     */
    public static QueueConfig createTest() {
        return new QueueConfig(MessageQueueFactory.QueueType.IN_MEMORY);
    }
} 