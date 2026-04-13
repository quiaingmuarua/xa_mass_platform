package com.xa.mass.base.channel.tranporter;

import java.util.concurrent.TimeUnit;

/**
 * 消息传输器接口，用于抽象消息的发送和接收功能
 * 隐藏内部队列实现细节，便于后续升级为多级队列或外部API调用
 * 
 * @param <T> 消息类型
 */
public interface MessageTransporter<T> {

    /**
     * 投递输入消息
     * @param message 消息对象
     */
    void sendInput(T message);

    /**
     * 消费输入消息
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 消息对象，如果超时返回null
     * @throws InterruptedException 如果等待被中断
     */
    T receiveInput(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * 投递输出消息
     * @param message 消息对象
     */
    void sendOutput(T message);

    /**
     * 消费输出消息
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 消息对象，如果超时返回null
     * @throws InterruptedException 如果等待被中断
     */
    T receiveOutput(long timeout, TimeUnit unit) throws InterruptedException;

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