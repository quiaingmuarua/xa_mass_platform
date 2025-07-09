package com.xa.mass.base.channel.queue;

/**
 * 结合队列和映射的消息存储接口
 * @param <K> 键类型
 * @param <V> 值类型
 */
public interface MessageQueueWithMap<K, V> extends MessageQueue<V>, MessageMap<K, V> {
    // 继承所有方法，无需额外定义
} 