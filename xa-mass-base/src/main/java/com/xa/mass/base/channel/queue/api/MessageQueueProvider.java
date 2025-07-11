package com.xa.mass.base.channel.queue.api;

/**
 * 消息队列提供者函数式接口
 * 用于创建不同类型的消息队列，支持函数式编程风格
 * 
 * @param <T> 消息类型
 */
@FunctionalInterface
public interface MessageQueueProvider<T> {
    
    /**
     * 创建消息队列
     * 
     * @param name 队列名称，用于标识和监控
     * @param type 消息类型Class，用于类型推断
     * @return 消息队列实例
     */
    MessageQueue<T> create(String name, Class<T> type);
    
    /**
     * 创建消息队列（简化版本，使用默认名称）
     * 
     * @param type 消息类型Class
     * @return 消息队列实例
     */
    default MessageQueue<T> create(Class<T> type) {
        return create("default", type);
    }
    
    /**
     * 创建消息队列（使用类型名作为队列名）
     * 
     * @param type 消息类型Class
     * @return 消息队列实例
     */
    default MessageQueue<T> createWithTypeName(Class<T> type) {
        return create(type.getSimpleName(), type);
    }
} 