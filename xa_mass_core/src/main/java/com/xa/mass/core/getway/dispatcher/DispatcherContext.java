package com.xa.mass.core.getway.dispatcher;

import com.google.gson.Gson;
import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.MessageTransporter;
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

    // 核心接口方法
    public MessageTransporter getMessageTransporter() { 
        return messageTransporter; 
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