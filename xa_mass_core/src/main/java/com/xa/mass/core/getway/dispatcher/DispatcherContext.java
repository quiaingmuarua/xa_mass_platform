package com.xa.mass.core.getway.dispatcher;

import com.google.gson.Gson;
import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.MessageQueue;
import com.xa.mass.core.getway.queue.MessageTransporter;
import com.xa.mass.core.getway.queue.QueueBasedMessageTransporter;
import com.xa.mass.core.getway.session.ServerSessionManager;

public class DispatcherContext {
    public enum MiddlewareDirection { INPUT, OUTPUT }

    private final MessageTransporter messageTransporter;
    private final ServerSessionManager sessionManager;
    private final Gson gson;
    private MessageHandlerRegistry messageHandlerRegistry;
    // ... 可扩展其它只读配置

    private MiddlewareDirection direction;

    /**
     * 构造函数 - 使用 MessageTransporter 接口
     */
    public DispatcherContext(
            MessageTransporter messageTransporter,
            ServerSessionManager sessionManager,
            Gson gson
    ) {
        this.messageTransporter = messageTransporter;
        this.sessionManager = sessionManager;
        this.gson = gson;
    }

    /**
     * 构造函数 - 向后兼容，自动包装队列为 MessageTransporter
     * @deprecated 建议使用 MessageTransporter 构造函数
     */
    @Deprecated
    public DispatcherContext(
            MessageQueue<Envelope> inputQueue,
            MessageQueue<Envelope> outputQueue,
            ServerSessionManager sessionManager,
            Gson gson
    ) {
        this(new QueueBasedMessageTransporter(inputQueue, outputQueue), sessionManager, gson);
    }

    // 新的接口方法
    public MessageTransporter getMessageTransporter() { 
        return messageTransporter; 
    }

    // 向后兼容的方法 - 如果内部是队列实现，则返回队列
    @Deprecated
    public MessageQueue<Envelope> getInputQueue() { 
        if (messageTransporter instanceof QueueBasedMessageTransporter) {
            return ((QueueBasedMessageTransporter) messageTransporter).getInputQueue();
        }
        throw new UnsupportedOperationException("当前 MessageTransporter 实现不支持直接访问队列");
    }
    
    @Deprecated
    public MessageQueue<Envelope> getOutputQueue() { 
        if (messageTransporter instanceof QueueBasedMessageTransporter) {
            return ((QueueBasedMessageTransporter) messageTransporter).getOutputQueue();
        }
        throw new UnsupportedOperationException("当前 MessageTransporter 实现不支持直接访问队列");
    }
    
    public ServerSessionManager getSessionManager() { return sessionManager; }
    public Gson getGson() { return gson; }

    public MessageHandlerRegistry getMessageHandlerRegistry() {
        return messageHandlerRegistry;
    }

    public void setMessageHandlerRegistry(MessageHandlerRegistry messageHandlerRegistry) {
        this.messageHandlerRegistry = messageHandlerRegistry;
    }

    public MiddlewareDirection getDirection() { return direction; }
    public void setDirection(MiddlewareDirection direction) { this.direction = direction; }
} 