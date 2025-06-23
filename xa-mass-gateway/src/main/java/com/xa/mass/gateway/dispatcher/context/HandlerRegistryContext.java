package com.xa.mass.gateway.dispatcher.context;

import com.xa.mass.gateway.dispatcher.MessageHandlerRegistry;

/**
 * 消息处理器注册表上下文接口
 * 提供消息处理器注册和管理功能
 */
public interface HandlerRegistryContext {
    /**
     * 获取消息处理器注册表
     * @return 消息处理器注册表
     */
    MessageHandlerRegistry getMessageHandlerRegistry();
    
    /**
     * 设置消息处理器注册表
     * @param messageHandlerRegistry 消息处理器注册表
     */
    void setMessageHandlerRegistry(MessageHandlerRegistry messageHandlerRegistry);
} 