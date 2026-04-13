package com.xa.mass.base.channel.tranporter;

import com.xa.mass.base.channel.messaging.api.MessageQueue;

import java.util.concurrent.TimeUnit;

/**
 * 基于队列的消息传输器实现
 * 包装现有的 MessageQueue 实现，提供统一的 MessageTransporter 接口
 * 
 * @param <T> 消息类型
 */
public class QueueBasedMessageTransporter<T> implements MessageTransporter<T> {

    private final MessageQueue<T> inputQueue;
    private final MessageQueue<T> outputQueue;

    public QueueBasedMessageTransporter(MessageQueue<T> inputQueue, MessageQueue<T> outputQueue) {
        this.inputQueue = inputQueue;
        this.outputQueue = outputQueue;
    }

    @Override
    public void sendInput(T message) {
        inputQueue.offer(message);
    }

    @Override
    public T receiveInput(long timeout, TimeUnit unit) throws InterruptedException {
        return inputQueue.poll(timeout, unit);
    }

    @Override
    public void sendOutput(T message) {
        outputQueue.offer(message);
    }

    @Override
    public T receiveOutput(long timeout, TimeUnit unit) throws InterruptedException {
        return outputQueue.poll(timeout, unit);
    }

    @Override
    public int inputQueueSize() {
        return inputQueue.size();
    }

    @Override
    public int outputQueueSize() {
        return outputQueue.size();
    }

    /**
     * 获取内部输入队列 - 仅用于向后兼容
     * @return 输入队列
     */
    public MessageQueue<T> getInputQueue() {
        return inputQueue;
    }

    /**
     * 获取内部输出队列 - 仅用于向后兼容
     * @return 输出队列
     */
    public MessageQueue<T> getOutputQueue() {
        return outputQueue;
    }
} 