package com.xa.mass.gateway.queue;

import java.util.concurrent.TimeUnit;

/**
 * 消息传输器接口，用于抽象消息的发送和接收功能
 * 隐藏内部队列实现细节，便于后续升级为多级队列或外部API调用
 */
public interface MessageTransporter {
    
    /**
     * 投递输入消息
     * @param envelope 消息信封
     */
    void sendInput(Envelope envelope);
    
    /**
     * 消费输入消息
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 消息信封，如果超时返回null
     * @throws InterruptedException 如果等待被中断
     */
    Envelope receiveInput(long timeout, TimeUnit unit) throws InterruptedException;
    
    /**
     * 投递输出消息
     * @param envelope 消息信封
     */
    void sendOutput(Envelope envelope);
    
    /**
     * 消费输出消息
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 消息信封，如果超时返回null
     * @throws InterruptedException 如果等待被中断
     */
    Envelope receiveOutput(long timeout, TimeUnit unit) throws InterruptedException;
    
    /**
     * 获取输入队列大小（监控API）
     * @return 队列大小
     */
    int inputQueueSize();
    
    /**
     * 获取输出队列大小（监控API）
     * @return 队列大小
     */
    int outputQueueSize();
} 