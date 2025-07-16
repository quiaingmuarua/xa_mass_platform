package com.xa.mass.gateway.queue;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.tranporter.ApiBasedMessageTransporter;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.channel.tranporter.MultiLevelMessageTransporter;
import com.xa.mass.base.channel.tranporter.QueueBasedMessageTransporter;

import java.util.concurrent.TimeUnit;

/**
 * Envelope 消息传输器
 * 专门用于处理 Envelope 类型消息的传输器实现
 * 包装泛型的 MessageTransporter 实现，提供类型安全的 Envelope 消息处理
 */
public class EnvelopeMessageTransporter implements MessageTransporter<Envelope> {

    private final MessageTransporter<Envelope> delegate;

    /**
     * 创建基于队列的 Envelope 消息传输器
     */
    public static EnvelopeMessageTransporter createQueueBased(MessageQueue<Envelope> inputQueue, MessageQueue<Envelope> outputQueue) {
        return new EnvelopeMessageTransporter(new QueueBasedMessageTransporter<>(inputQueue, outputQueue));
    }

    /**
     * 创建多级队列的 Envelope 消息传输器
     */
    public static EnvelopeMessageTransporter createMultiLevel() {
        return new EnvelopeMessageTransporter(new MultiLevelMessageTransporter<>());
    }

    /**
     * 创建基于外部API的 Envelope 消息传输器
     */
    public static EnvelopeMessageTransporter createApiBased(String inputApiUrl, String outputApiUrl, String apiKey) {
        return new EnvelopeMessageTransporter(new ApiBasedMessageTransporter<>(inputApiUrl, outputApiUrl, apiKey));
    }

    /**
     * 私有构造函数，使用委托模式
     */
    private EnvelopeMessageTransporter(MessageTransporter<Envelope> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void sendInput(Envelope message) {
        delegate.sendInput(message);
    }

    @Override
    public Envelope receiveInput(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.receiveInput(timeout, unit);
    }

    @Override
    public void sendOutput(Envelope message) {
        delegate.sendOutput(message);
    }

    @Override
    public Envelope receiveOutput(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.receiveOutput(timeout, unit);
    }

    @Override
    public int inputQueueSize() {
        return delegate.inputQueueSize();
    }

    @Override
    public int outputQueueSize() {
        return delegate.outputQueueSize();
    }

    /**
     * 获取委托的传输器实例（用于特殊操作）
     */
    public MessageTransporter<Envelope> getDelegate() {
        return delegate;
    }
} 