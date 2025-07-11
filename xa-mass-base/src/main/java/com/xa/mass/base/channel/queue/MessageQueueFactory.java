package com.xa.mass.base.channel.queue;

/**
 * MessageQueue 工厂类
 * 提供不同队列实现的选择，便于配置和切换
 * 
 * @param <T> 消息类型
 */
public class MessageQueueFactory<T> {

    /**
     * 队列类型枚举
     */
    public enum QueueType {
        IN_MEMORY,      // 内存队列
        REDIS,          // Redis队列
        DATABASE,       // 数据库队列
        // 未来可以添加更多类型
        // KAFKA,
        // RABBITMQ,
        // SQS
    }

    /**
     * 创建内存队列
     */
    public static <T> MessageQueue<T> createInMemory() {
        return new InMemoryMessageQueue<>();
    }

    /**
     * 创建带名称的内存队列
     */
    public static <T> MessageQueue<T> createInMemory(String name) {
        return new InMemoryMessageQueue<>();
    }

    /**
     * 根据类型创建队列
     */
    public static <T> MessageQueue<T> create(QueueType type, Object... params) {
        switch (type) {
            case IN_MEMORY:
                if (params.length > 0 && params[0] instanceof String) {
                    return createInMemory((String) params[0]);
                }
                return createInMemory();

            case REDIS:
                // TODO: 实现Redis队列
                throw new UnsupportedOperationException("Redis queue not implemented yet");

            case DATABASE:
                // TODO: 实现数据库队列
                throw new UnsupportedOperationException("Database queue not implemented yet");

            default:
                throw new IllegalArgumentException("不支持的队列类型: " + type);
        }
    }

    /**
     * 创建默认队列（内存队列）
     */
    public static <T> MessageQueue<T> createDefault() {
        return createInMemory();
    }

    /**
     * 批量创建队列
     */
    public static <T> MessageQueue<T>[] createMultiple(QueueType type, int count) {
        @SuppressWarnings("unchecked")
        MessageQueue<T>[] queues = new MessageQueue[count];
        for (int i = 0; i < count; i++) {
            queues[i] = create(type);
        }
        return queues;
    }
} 