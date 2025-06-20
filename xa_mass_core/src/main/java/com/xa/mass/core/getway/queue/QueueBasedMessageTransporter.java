package com.xa.mass.core.getway.queue;

import java.util.concurrent.TimeUnit;

/**
 * 基于队列的消息传输器实现
 * 包装现有的 MessageQueue 实现，提供统一的 MessageTransporter 接口
 */
public class QueueBasedMessageTransporter implements MessageTransporter {
    
    private final MessageQueue<Envelope> inputQueue;
    private final MessageQueue<Envelope> outputQueue;
    
    public QueueBasedMessageTransporter(MessageQueue<Envelope> inputQueue, MessageQueue<Envelope> outputQueue) {
        this.inputQueue = inputQueue;
        this.outputQueue = outputQueue;
    }
    
    @Override
    public void sendInput(Envelope envelope) {
        inputQueue.offer(envelope);
    }
    
    @Override
    public Envelope receiveInput(long timeout, TimeUnit unit) throws InterruptedException {
        return inputQueue.poll(timeout, unit);
    }
    
    @Override
    public void sendOutput(Envelope envelope) {
        outputQueue.offer(envelope);
    }
    
    @Override
    public Envelope receiveOutput(long timeout, TimeUnit unit) throws InterruptedException {
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
    public MessageQueue<Envelope> getInputQueue() {
        return inputQueue;
    }
    
    /**
     * 获取内部输出队列 - 仅用于向后兼容
     * @return 输出队列
     */
    public MessageQueue<Envelope> getOutputQueue() {
        return outputQueue;
    }
} 