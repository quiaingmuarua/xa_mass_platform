package com.xa.mass.core.getway.dispatcher;

import com.google.gson.Gson;
import com.xa.mass.core.getway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.core.getway.queue.MessageTransporter;
import com.xa.mass.core.getway.session.ServerSessionManager;

/**
 * 分发器上下文
 * 实现 DispatchRuntimeContext 接口，提供完整的分发运行时环境
 */
public class DispatcherContext implements DispatchRuntimeContext {
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

    // SessionContext 实现
    @Override
    public ServerSessionManager getSessionManager() { 
        return sessionManager; 
    }

    // CodecContext 实现
    @Override
    public Gson getGson() { 
        return gson; 
    }

    // TransportContext 实现
    @Override
    public MessageTransporter getMessageTransporter() { 
        return messageTransporter; 
    }

    // HandlerRegistryContext 实现
    @Override
    public MessageHandlerRegistry getMessageHandlerRegistry() {
        return messageHandlerRegistry;
    }

    public void setMessageHandlerRegistry(MessageHandlerRegistry messageHandlerRegistry) {
        this.messageHandlerRegistry = messageHandlerRegistry;
    }

    // MiddlewareContext 实现
    @Override
    public MiddlewareDirection getDirection() { 
        return direction; 
    }
    
    @Override
    public void setDirection(MiddlewareDirection direction) { 
        this.direction = direction; 
    }
} 