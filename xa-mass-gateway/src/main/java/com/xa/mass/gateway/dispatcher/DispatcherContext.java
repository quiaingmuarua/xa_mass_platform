package com.xa.mass.gateway.dispatcher;

import com.google.gson.Gson;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.transport.WorkerEndpointRegistry;

/**
 * Concrete gateway dispatch runtime context.
 */
public class DispatcherContext implements DispatchRuntimeContext {
    private final MessageTransporter messageTransporter;
    private final WorkerEndpointRegistry sessionManager;
    private final MessageCodec messageCodec;
    private MessageHandlerRegistry messageHandlerRegistry;
    // ... 可扩展其它只读配置

    /**
     * 构造函数 - 使用 MessageTransporter 和 MessageCodec 接口
     */
    public DispatcherContext(
            MessageTransporter messageTransporter,
            WorkerEndpointRegistry sessionManager,
            MessageCodec messageCodec
    ) {
        this.messageTransporter = messageTransporter;
        this.sessionManager = sessionManager;
        this.messageCodec = messageCodec;
    }

    /**
     * 构造函数 - 向后兼容，自动创建 GsonMessageCodec
     */
    public DispatcherContext(
            MessageTransporter messageTransporter,
            WorkerEndpointRegistry sessionManager,
            Gson gson
    ) {
        this.messageTransporter = messageTransporter;
        this.sessionManager = sessionManager;
        this.messageCodec = new com.xa.mass.gateway.queue.GsonMessageCodec(gson);
    }

    @Override
    public WorkerEndpointRegistry getSessionManager() {
        return sessionManager;
    }

    @Override
    public MessageCodec getMessageCodec() {
        return messageCodec;
    }

    @Override
    public MessageTransporter getMessageTransporter() {
        return messageTransporter;
    }

    @Override
    public MessageHandlerRegistry getMessageHandlerRegistry() {
        return messageHandlerRegistry;
    }

    public void setMessageHandlerRegistry(MessageHandlerRegistry messageHandlerRegistry) {
        this.messageHandlerRegistry = messageHandlerRegistry;
    }

}
