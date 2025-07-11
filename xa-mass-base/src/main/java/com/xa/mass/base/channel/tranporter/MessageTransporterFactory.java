package com.xa.mass.base.channel.tranporter;


import com.xa.mass.base.channel.queue.api.MessageQueue;

/**
 * MessageTransporter 工厂类
 * 提供不同实现的选择，便于配置和切换
 * 
 * @param <T> 消息类型
 */
public class MessageTransporterFactory<T> {

    /**
     * 创建基于队列的消息传输器
     */
    public static <T> MessageTransporter<T> createQueueBased(MessageQueue<T> inputQueue, MessageQueue<T> outputQueue) {
        return new QueueBasedMessageTransporter<>(inputQueue, outputQueue);
    }

    /**
     * 创建多级队列消息传输器
     */
    public static <T> MessageTransporter<T> createMultiLevel() {
        return new MultiLevelMessageTransporter<>();
    }

    /**
     * 创建基于外部API的消息传输器
     */
    public static <T> MessageTransporter<T> createApiBased(String inputApiUrl, String outputApiUrl, String apiKey) {
        return new ApiBasedMessageTransporter<>(inputApiUrl, outputApiUrl, apiKey);
    }

    /**
     * 根据类型创建消息传输器
     */
    public static <T> MessageTransporter<T> create(TransporterType type, Object... params) {
        switch (type) {
            case QUEUE_BASED:
                if (params.length >= 2 && params[0] instanceof MessageQueue && params[1] instanceof MessageQueue) {
                    @SuppressWarnings("unchecked")
                    MessageQueue<T> inputQueue = (MessageQueue<T>) params[0];
                    @SuppressWarnings("unchecked")
                    MessageQueue<T> outputQueue = (MessageQueue<T>) params[1];
                    return createQueueBased(inputQueue, outputQueue);
                }
                throw new IllegalArgumentException("QUEUE_BASED 类型需要提供 inputQueue 和 outputQueue 参数");

            case MULTI_LEVEL:
                return createMultiLevel();

            case API_BASED:
                if (params.length >= 3 && params[0] instanceof String && params[1] instanceof String && params[2] instanceof String) {
                    return createApiBased((String) params[0], (String) params[1], (String) params[2]);
                }
                throw new IllegalArgumentException("API_BASED 类型需要提供 inputApiUrl, outputApiUrl 和 apiKey 参数");

            default:
                throw new IllegalArgumentException("不支持的传输器类型: " + type);
        }
    }

    /**
     * 传输器类型枚举
     */
    public enum TransporterType {
        QUEUE_BASED,      // 基于队列的实现
        MULTI_LEVEL,      // 多级队列实现
        API_BASED         // 基于外部API的实现
    }
} 